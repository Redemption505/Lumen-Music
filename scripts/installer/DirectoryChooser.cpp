#include <windows.h>
#include <msi.h>
#include <msiquery.h>
#include <shobjidl.h>
#include <shlwapi.h>
#include <string>
#include <vector>
#include <thread>

static std::wstring Property(MSIHANDLE session, const wchar_t* name) {
    DWORD size = 0;
    if (MsiGetPropertyW(session, name, L"", &size) != ERROR_MORE_DATA) return L"";
    std::vector<wchar_t> value(size + 1);
    DWORD capacity = static_cast<DWORD>(value.size());
    return MsiGetPropertyW(session, name, value.data(), &capacity) == ERROR_SUCCESS ? std::wstring(value.data()) : L"";
}

extern "C" __declspec(dllexport) UINT __stdcall PreserveInstallDirectory(MSIHANDLE session) {
    if (!Property(session, L"INSTALLDIR").empty()) return ERROR_SUCCESS;
    const auto family = Property(session, L"UpgradeCode");
    if (family.empty()) return ERROR_INSTALL_FAILURE;
    wchar_t product[39] = {};
    for (DWORD index = 0; MsiEnumRelatedProductsW(family.c_str(), 0, index, product) == ERROR_SUCCESS; ++index) {
        DWORD size = 0;
        if (MsiGetProductInfoW(product, INSTALLPROPERTY_INSTALLLOCATION, L"", &size) != ERROR_MORE_DATA) continue;
        std::vector<wchar_t> location(size + 1);
        DWORD capacity = static_cast<DWORD>(location.size());
        if (MsiGetProductInfoW(product, INSTALLPROPERTY_INSTALLLOCATION, location.data(), &capacity) == ERROR_SUCCESS &&
            location[0] && !PathIsRelativeW(location.data())) {
            return MsiSetPropertyW(session, L"INSTALLDIR", location.data());
        }
    }
    return ERROR_SUCCESS;
}

extern "C" __declspec(dllexport) UINT __stdcall ChooseDirectory(MSIHANDLE session) {
    // Capture all MSI state on its calling thread; only the Shell dialog runs on the STA worker.
    const auto initial = Property(session, L"INSTALLDIR");
    // Return a separate result so the MSI UI event, rather than this custom action,
    // changes INSTALLDIR and updates its existing PathEdit control. Cancellation
    // returns the current path even after an earlier successful selection.
    const auto initializedResult = MsiSetPropertyW(session, L"LUMEN_CHOSEN_INSTALLDIR", initial.c_str());
    if (initializedResult != ERROR_SUCCESS) return initializedResult;
    const auto ownerText = Property(session, L"MsiDialogHandle");
    const HWND owner = reinterpret_cast<HWND>(_wcstoui64(ownerText.c_str(), nullptr, 10));
    std::wstring selected;
    HRESULT outcome = S_OK;
    std::thread worker([&]() {
        const HRESULT initialized = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
        if (FAILED(initialized)) { outcome = initialized; return; }
        IFileDialog* dialog = nullptr;
        IShellItem* folder = nullptr;
        IShellItem* result = nullptr;
        PWSTR displayName = nullptr;
        outcome = CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&dialog));
        if (SUCCEEDED(outcome)) {
            FILEOPENDIALOGOPTIONS options = 0;
            outcome = dialog->GetOptions(&options);
            if (SUCCEEDED(outcome)) outcome = dialog->SetOptions(options | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST);
            if (SUCCEEDED(outcome)) outcome = dialog->SetTitle(L"选择 Lumen Music 安装文件夹");
            if (SUCCEEDED(outcome)) outcome = dialog->SetOkButtonLabel(L"选择文件夹");
            auto existing = initial;
            while (!existing.empty() && GetFileAttributesW(existing.c_str()) == INVALID_FILE_ATTRIBUTES) {
                const auto slash = existing.find_last_of(L"\\/");
                if (slash == std::wstring::npos) { existing.clear(); break; }
                existing.resize(slash);
            }
            if (!existing.empty() && SUCCEEDED(SHCreateItemFromParsingName(existing.c_str(), nullptr, IID_PPV_ARGS(&folder)))) dialog->SetFolder(folder);
            if (SUCCEEDED(outcome)) outcome = dialog->Show(owner);
            if (SUCCEEDED(outcome)) outcome = dialog->GetResult(&result);
            if (SUCCEEDED(outcome)) outcome = result->GetDisplayName(SIGDN_FILESYSPATH, &displayName);
            if (SUCCEEDED(outcome)) selected = displayName;
        }
        CoTaskMemFree(displayName);
        if (result) result->Release();
        if (folder) folder->Release();
        if (dialog) dialog->Release();
        CoUninitialize();
    });
    worker.join();
    if (outcome == HRESULT_FROM_WIN32(ERROR_CANCELLED)) return ERROR_SUCCESS;
    if (FAILED(outcome)) return ERROR_INSTALL_FAILURE;
    if (!selected.empty()) return MsiSetPropertyW(session, L"LUMEN_CHOSEN_INSTALLDIR", (selected + L"\\").c_str());
    return ERROR_SUCCESS;
}
