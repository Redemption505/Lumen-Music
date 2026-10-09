use lumen_stream_proxy::{app, parse_listen_addr, parse_loopback_url};
use std::env;
use tokio::net::TcpListener;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let listen = parse_listen_addr(
        &env::var("LUMEN_PROXY_ADDR").unwrap_or_else(|_| "127.0.0.1:8766".into()),
    )?;
    let python = parse_loopback_url(
        &env::var("LUMEN_PYTHON_SERVICE_URL").unwrap_or_else(|_| "http://127.0.0.1:8765".into()),
    )?;
    let listener = TcpListener::bind(listen).await?;
    println!("Lumen stream proxy listening on http://{listen}");
    println!("Python audio upstream: {python}");
    axum::serve(listener, app(python)).with_graceful_shutdown(async {
        let _ = tokio::signal::ctrl_c().await;
    }).await?;
    Ok(())
}
