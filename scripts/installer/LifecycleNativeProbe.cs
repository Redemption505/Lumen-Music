using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

public static class LifecycleNativeProbe
{
    [DllImport("msi.dll", CharSet=CharSet.Unicode)] private static extern uint MsiOpenPackageExW(string path, uint flags, out uint session);
    [DllImport("msi.dll", CharSet=CharSet.Unicode)] private static extern uint MsiGetPropertyW(uint session, string name, StringBuilder value, ref uint size);
    [DllImport("msi.dll", CharSet=CharSet.Unicode)] private static extern uint MsiSetPropertyW(uint session, string name, string value);
    [DllImport("msi.dll")] private static extern uint MsiCloseHandle(uint session);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] private static extern IntPtr LoadLibraryW(string path);
    [DllImport("kernel32.dll", CharSet=CharSet.Ansi, SetLastError=true)] private static extern IntPtr GetProcAddress(IntPtr module, string name);
    [DllImport("kernel32.dll")] private static extern bool FreeLibrary(IntPtr module);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] private delegate uint Preserve(uint session);
    private static string Property(uint session, string name) {
        uint size=0; MsiGetPropertyW(session,name,null,ref size);
        var result=new StringBuilder((int)size+1); uint capacity=(uint)result.Capacity;
        if(MsiGetPropertyW(session,name,result,ref capacity)!=0) throw new InvalidOperationException("Cannot read MSI test session property.");
        return result.ToString();
    }
    public static string Probe(string qaMsi, string nativeDll, string qaFamily, string qaName) {
        uint session=0; IntPtr module=IntPtr.Zero;
        try {
            uint open=MsiOpenPackageExW(qaMsi,0,out session);
            if(open!=0) throw new InvalidOperationException("Cannot open QA package session: "+open);
            if(Property(session,"UpgradeCode")!=qaFamily || Property(session,"ProductName")!=qaName) throw new InvalidOperationException("Native probe rejected a non-QA package session.");
            if(MsiSetPropertyW(session,"INSTALLDIR","")!=0) throw new InvalidOperationException("Cannot clear QA INSTALLDIR.");
            module=LoadLibraryW(nativeDll); if(module==IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            IntPtr export=GetProcAddress(module,"PreserveInstallDirectory"); if(export==IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            var preserve=(Preserve)Marshal.GetDelegateForFunctionPointer(export,typeof(Preserve));
            uint status=preserve(session); if(status!=0) throw new InvalidOperationException("Native preserve action failed: "+status);
            return Property(session,"INSTALLDIR");
        } finally { if(module!=IntPtr.Zero) FreeLibrary(module); if(session!=0) MsiCloseHandle(session); }
    }
}
