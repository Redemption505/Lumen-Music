use axum::{
    body::Body,
    extract::{Path, State},
    http::{header, HeaderValue, Method, Request, StatusCode},
    response::Response,
    routing::get,
    Router,
};
use futures_util::TryStreamExt;
use reqwest::Url;
use std::{net::SocketAddr, sync::Arc};

#[derive(Clone)]
struct AppState {
    client: reqwest::Client,
    upstream: Url,
}

pub fn app(upstream: Url) -> Router {
    let client = reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .expect("static HTTP client configuration is valid");
    let state = AppState { client, upstream };
    Router::new()
        .route("/health", get(health))
        .route("/api/tracks/{track_id}/stream", get(stream).head(stream))
        .with_state(Arc::new(state))
}

async fn health(State(state): State<Arc<AppState>>) -> Response<Body> {
    Response::builder()
        .status(StatusCode::OK)
        .header("x-lumen-upstream", state.upstream.as_str())
        .body(Body::from("ok"))
        .expect("loopback upstream is a valid header value")
}

pub fn parse_loopback_url(value: &str) -> Result<Url, String> {
    let url = Url::parse(value).map_err(|error| format!("invalid Python service URL: {error}"))?;
    let loopback = match url.host() {
        Some(url::Host::Ipv4(address)) => address.is_loopback(),
        Some(url::Host::Ipv6(address)) => address.is_loopback(),
        Some(url::Host::Domain(domain)) => domain.eq_ignore_ascii_case("localhost"),
        None => false,
    };
    if url.scheme() != "http" || !loopback || url.port().is_none()
        || !url.username().is_empty() || url.password().is_some()
    {
        return Err("Python service URL must be http://localhost or a loopback IP with an explicit port".into());
    }
    if url.query().is_some() || url.fragment().is_some() {
        return Err("Python service URL cannot contain a query or fragment".into());
    }
    Ok(url)
}

pub fn parse_listen_addr(value: &str) -> Result<SocketAddr, String> {
    let address: SocketAddr = value.parse().map_err(|error| format!("invalid listen address: {error}"))?;
    if !address.ip().is_loopback() {
        return Err("listen address must be loopback".into());
    }
    Ok(address)
}

async fn stream(
    State(state): State<Arc<AppState>>,
    Path(track_id): Path<String>,
    request: Request<Body>,
) -> Response<Body> {
    if track_id.is_empty() || track_id.contains('/') {
        return error_response(StatusCode::BAD_REQUEST, "INVALID_TRACK_ID");
    }
    let mut upstream_url = state.upstream.clone();
    {
        let mut segments = match upstream_url.path_segments_mut() {
            Ok(segments) => segments,
            Err(_) => return error_response(StatusCode::BAD_GATEWAY, "INVALID_UPSTREAM_URL"),
        };
        segments.pop_if_empty();
        segments.extend(["api", "tracks", &track_id, "stream"]);
    }

    let mut upstream_request = if request.method() == Method::HEAD {
        state.client.head(upstream_url)
    } else {
        state.client.get(upstream_url)
    };
    if let Some(range) = request.headers().get(header::RANGE) {
        upstream_request = upstream_request.header(header::RANGE, range.clone());
    }
    let upstream = match upstream_request.send().await {
        Ok(response) => response,
        Err(_) => return error_response(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE"),
    };

    let status = StatusCode::from_u16(upstream.status().as_u16()).unwrap_or(StatusCode::BAD_GATEWAY);
    let upstream_headers = upstream.headers().clone();
    let mut response = Response::builder().status(status);
    for name in [
        header::CONTENT_TYPE,
        header::CONTENT_LENGTH,
        header::CONTENT_RANGE,
        header::ACCEPT_RANGES,
    ] {
        if let Some(value) = upstream_headers.get(&name) {
            response = response.header(name, value.clone());
        }
    }
    if let Some(code) = upstream_headers.get("x-lumen-error-code") {
        response = response.header("x-lumen-error-code", code.clone());
    }

    let body = if request.method() == Method::HEAD || status == StatusCode::NOT_MODIFIED {
        Body::empty()
    } else {
        // Dropping this body drops reqwest's byte stream and closes the upstream response.
        Body::from_stream(upstream.bytes_stream().map_err(|error| {
            std::io::Error::new(std::io::ErrorKind::Other, error)
        }))
    };
    response.body(body).unwrap_or_else(|_| error_response(StatusCode::BAD_GATEWAY, "PROXY_RESPONSE_ERROR"))
}

fn error_response(status: StatusCode, code: &'static str) -> Response<Body> {
    Response::builder()
        .status(status)
        .header("x-lumen-error-code", HeaderValue::from_static(code))
        .body(Body::empty())
        .expect("static proxy error response is valid")
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{body::Bytes, http::HeaderValue, response::IntoResponse, routing::any};
    use futures_util::{stream, StreamExt};
    use std::{convert::Infallible, net::SocketAddr, sync::atomic::{AtomicUsize, Ordering}, time::Duration};
    use tokio::{net::TcpListener, sync::Notify, task::JoinHandle};

    async fn spawn(router: Router) -> (SocketAddr, JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let task = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        (address, task)
    }

    fn service_url(address: SocketAddr) -> Url {
        Url::parse(&format!("http://{address}/")).unwrap()
    }

    struct DropNotice(Arc<Notify>);
    impl Drop for DropNotice {
        fn drop(&mut self) { self.0.notify_one(); }
    }

    #[tokio::test]
    async fn forwards_first_and_remote_ranges_as_streamed_206() {
        let upstream = Router::new().route("/api/tracks/{id}/stream", any(|request: Request<Body>| async move {
            let range = request.headers().get(header::RANGE).unwrap().to_str().unwrap();
            let (start, end) = if range == "bytes=0-3" { (0, 3) } else { (8, 11) };
            let mut response = Response::new(Body::from(vec![0u8; (end - start + 1) as usize]));
            *response.status_mut() = StatusCode::PARTIAL_CONTENT;
            response.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static("audio/mp4"));
            response.headers_mut().insert(header::CONTENT_LENGTH, HeaderValue::from_str(&(end-start+1).to_string()).unwrap());
            response.headers_mut().insert(header::CONTENT_RANGE, HeaderValue::from_str(&format!("bytes {start}-{end}/20")).unwrap());
            response.headers_mut().insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
            response
        }));
        let (upstream_addr, upstream_task) = spawn(upstream).await;
        let (proxy_addr, proxy_task) = spawn(app(service_url(upstream_addr))).await;
        let client = reqwest::Client::new();
        for range in ["bytes=0-3", "bytes=8-11"] {
            let response = client.get(format!("http://{proxy_addr}/api/tracks/id/stream"))
                .header(header::RANGE, range).send().await.unwrap();
            assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
            assert_eq!(response.headers()[header::CONTENT_RANGE], if range == "bytes=0-3" { "bytes 0-3/20" } else { "bytes 8-11/20" });
            assert_eq!(response.headers()[header::CONTENT_TYPE], "audio/mp4");
            assert_eq!(response.headers()[header::ACCEPT_RANGES], "bytes");
            assert_eq!(response.bytes().await.unwrap().len(), 4);
        }
        proxy_task.abort(); upstream_task.abort();
    }

    #[tokio::test]
    async fn forwards_head_without_a_body() {
        let upstream = Router::new().route("/api/tracks/{id}/stream", any(|request: Request<Body>| async move {
            assert_eq!(request.method(), Method::HEAD);
            let mut response = StatusCode::OK.into_response();
            response.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static("audio/mp4"));
            response.headers_mut().insert(header::CONTENT_LENGTH, HeaderValue::from_static("500"));
            response.headers_mut().insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
            response
        }));
        let (upstream_addr, upstream_task) = spawn(upstream).await;
        let (proxy_addr, proxy_task) = spawn(app(service_url(upstream_addr))).await;
        let response = reqwest::Client::new().head(format!("http://{proxy_addr}/api/tracks/a/stream"))
            .send().await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()[header::CONTENT_LENGTH], "500");
        assert!(response.bytes().await.unwrap().is_empty());
        proxy_task.abort(); upstream_task.abort();
    }

    #[tokio::test]
    async fn loopback_forwarding_ignores_environment_proxies() {
        let upstream_hits = Arc::new(AtomicUsize::new(0));
        let upstream_counter = upstream_hits.clone();
        let upstream = Router::new().route("/api/tracks/{id}/stream", any(move |request: Request<Body>| {
            let counter = upstream_counter.clone();
            async move {
                assert_eq!(request.method(), Method::HEAD);
                counter.fetch_add(1, Ordering::SeqCst);
                let mut response = StatusCode::OK.into_response();
                response.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static("audio/mp4"));
                response.headers_mut().insert(header::CONTENT_LENGTH, HeaderValue::from_static("500"));
                response
            }
        }));
        let proxy_hits = Arc::new(AtomicUsize::new(0));
        let proxy_counter = proxy_hits.clone();
        let environment_proxy = Router::new().fallback(any(move || {
            let counter = proxy_counter.clone();
            async move {
                counter.fetch_add(1, Ordering::SeqCst);
                StatusCode::BAD_GATEWAY
            }
        }));
        let (upstream_addr, upstream_task) = spawn(upstream).await;
        let (environment_proxy_addr, environment_proxy_task) = spawn(environment_proxy).await;
        let proxy_url = format!("http://{environment_proxy_addr}");
        // Only the child receives proxy variables, leaving concurrently running tests untouched.
        let output = tokio::task::spawn_blocking(move || {
            let mut command = std::process::Command::new(std::env::current_exe().unwrap());
            command.args(["--exact", "tests::loopback_proxy_child", "--ignored", "--nocapture"]);
            for name in ["HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy"] {
                command.env(name, &proxy_url);
            }
            command.env("NO_PROXY", "").env("no_proxy", "")
                .env("LUMEN_TEST_UPSTREAM", service_url(upstream_addr).as_str())
                .output().unwrap()
        }).await.unwrap();
        upstream_task.abort();
        environment_proxy_task.abort();
        assert!(output.status.success(), "child failed: {}{}",
            String::from_utf8_lossy(&output.stdout), String::from_utf8_lossy(&output.stderr));
        assert_eq!(upstream_hits.load(Ordering::SeqCst), 1);
        assert_eq!(proxy_hits.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    #[ignore = "subprocess helper for isolated proxy environment"]
    async fn loopback_proxy_child() {
        let upstream = Url::parse(&std::env::var("LUMEN_TEST_UPSTREAM").unwrap()).unwrap();
        let (proxy_addr, proxy_task) = spawn(app(upstream)).await;
        let response = reqwest::Client::builder().no_proxy().timeout(Duration::from_secs(3))
            .build().unwrap().head(format!("http://{proxy_addr}/api/tracks/a/stream"))
            .send().await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()[header::CONTENT_TYPE], "audio/mp4");
        assert_eq!(response.headers()[header::CONTENT_LENGTH], "500");
        assert!(response.bytes().await.unwrap().is_empty());
        proxy_task.abort();
    }

    #[tokio::test]
    async fn health_route_reports_ok() {
        let (address, task) = spawn(app(service_url("127.0.0.1:8765".parse().unwrap()))).await;
        let response = reqwest::get(format!("http://{address}/health")).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["x-lumen-upstream"], "http://127.0.0.1:8765/");
        assert_eq!(response.text().await.unwrap(), "ok");
        task.abort();
    }

    #[tokio::test]
    async fn forwards_range_errors_and_lumen_error_code() {
        let upstream = Router::new().route("/api/tracks/{id}/stream", any(|| async {
            let mut response = StatusCode::RANGE_NOT_SATISFIABLE.into_response();
            response.headers_mut().insert("x-lumen-error-code", HeaderValue::from_static("RANGE_NOT_SATISFIABLE"));
            response.headers_mut().insert(header::CONTENT_RANGE, HeaderValue::from_static("bytes */20"));
            response
        }));
        let (upstream_addr, upstream_task) = spawn(upstream).await;
        let (proxy_addr, proxy_task) = spawn(app(service_url(upstream_addr))).await;
        let response = reqwest::Client::new().get(format!("http://{proxy_addr}/api/tracks/a/stream"))
            .header(header::RANGE, "bytes=99-").send().await.unwrap();
        assert_eq!(response.status(), StatusCode::RANGE_NOT_SATISFIABLE);
        assert_eq!(response.headers()["x-lumen-error-code"], "RANGE_NOT_SATISFIABLE");
        assert_eq!(response.headers()[header::CONTENT_RANGE], "bytes */20");
        proxy_task.abort(); upstream_task.abort();
    }

    #[tokio::test]
    async fn dropping_client_body_cancels_upstream_stream() {
        let dropped = Arc::new(Notify::new());
        let dropped_for_handler = dropped.clone();
        let produced = Arc::new(AtomicUsize::new(0));
        let produced_stream = produced.clone();
        let upstream = Router::new().route("/api/tracks/{id}/stream", any(move || {
            let notice = DropNotice(dropped_for_handler.clone());
            let counter = produced_stream.clone();
            async move {
                let body_stream = stream::unfold((0usize, notice), move |(index, notice)| {
                    let counter = counter.clone();
                    async move {
                        tokio::time::sleep(Duration::from_millis(5)).await;
                        counter.fetch_add(1, Ordering::SeqCst);
                        Some((Ok::<Bytes, Infallible>(Bytes::from(vec![1u8; 1024])), (index+1, notice)))
                    }
                });
                let body = Body::from_stream(body_stream);
                (StatusCode::OK, body)
            }
        }));
        let (upstream_addr, upstream_task) = spawn(upstream).await;
        let (proxy_addr, proxy_task) = spawn(app(service_url(upstream_addr))).await;
        let response = reqwest::get(format!("http://{proxy_addr}/api/tracks/a/stream")).await.unwrap();
        let mut body = response.bytes_stream();
        let _ = body.next().await.unwrap().unwrap();
        drop(body);
        tokio::time::timeout(Duration::from_secs(2), dropped.notified()).await.unwrap();
        assert!(produced.load(Ordering::SeqCst) < 1000);
        proxy_task.abort(); upstream_task.abort();
    }

    #[test]
    fn only_loopback_addresses_are_accepted() {
        assert!(parse_loopback_url("http://127.0.0.1:8765").is_ok());
        assert!(parse_loopback_url("http://example.com:8765").is_err());
        assert!(parse_listen_addr("127.0.0.1:0").is_ok());
        assert!(parse_listen_addr("0.0.0.0:8766").is_err());
    }
}
