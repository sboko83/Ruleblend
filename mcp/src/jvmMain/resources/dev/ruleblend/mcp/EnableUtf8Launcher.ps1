param([Parameter(Mandatory = $true)][string]$Path)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

# Update only the generated executable's manifest. Keep its existing DPI, privilege and OS flags.
Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class LauncherManifest {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr LoadLibraryEx(string path, IntPtr file, uint flags);
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool FreeLibrary(IntPtr module);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr FindResource(IntPtr module, IntPtr name, IntPtr type);
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern uint SizeofResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr LockResource(IntPtr resource);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr BeginUpdateResource(string path, bool deleteExisting);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool UpdateResource(IntPtr update, IntPtr type, IntPtr name, ushort language, byte[] data, uint size);
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool EndUpdateResource(IntPtr update, bool discard);
    public static void Check(bool success) {
        if (!success) throw new Win32Exception(Marshal.GetLastWin32Error());
    }
}
'@

$module = [LauncherManifest]::LoadLibraryEx($Path, [IntPtr]::Zero, 2)
[LauncherManifest]::Check($module -ne [IntPtr]::Zero)
try {
    $resource = [LauncherManifest]::FindResource($module, [IntPtr]1, [IntPtr]24)
    [LauncherManifest]::Check($resource -ne [IntPtr]::Zero)
    $size = [LauncherManifest]::SizeofResource($module, $resource)
    $data = [byte[]]::new($size)
    $pointer = [LauncherManifest]::LockResource([LauncherManifest]::LoadResource($module, $resource))
    [LauncherManifest]::Check($pointer -ne [IntPtr]::Zero)
    [Runtime.InteropServices.Marshal]::Copy($pointer, $data, 0, $size)
} finally {
    [void][LauncherManifest]::FreeLibrary($module)
}

$document = [xml][Text.Encoding]::UTF8.GetString($data).TrimEnd([char]0)
$ns = [Xml.XmlNamespaceManager]::new($document.NameTable)
$ns.AddNamespace('v3', 'urn:schemas-microsoft-com:asm.v3')
$ns.AddNamespace('utf8', 'http://schemas.microsoft.com/SMI/2019/WindowsSettings')
$settings = $document.SelectSingleNode('//v3:application/v3:windowsSettings', $ns)
if ($null -eq $settings) {
    $application = $document.CreateElement('application', 'urn:schemas-microsoft-com:asm.v3')
    $settings = $document.CreateElement('windowsSettings', 'urn:schemas-microsoft-com:asm.v3')
    [void]$application.AppendChild($settings)
    [void]$document.DocumentElement.AppendChild($application)
}
$codepage = $settings.SelectSingleNode('utf8:activeCodePage', $ns)
if ($null -eq $codepage) {
    $codepage = $document.CreateElement('activeCodePage', 'http://schemas.microsoft.com/SMI/2019/WindowsSettings')
    [void]$settings.AppendChild($codepage)
}
$codepage.InnerText = 'UTF-8'
$updated = [Text.Encoding]::UTF8.GetBytes($document.OuterXml)
$handle = [LauncherManifest]::BeginUpdateResource($Path, $false)
[LauncherManifest]::Check($handle -ne [IntPtr]::Zero)
try {
    # OpenJDK's Windows launchers carry the manifest as resource 1, language 1033.
    [LauncherManifest]::Check([LauncherManifest]::UpdateResource($handle, [IntPtr]24, [IntPtr]1, 1033, $updated, $updated.Length))
} catch {
    [void][LauncherManifest]::EndUpdateResource($handle, $true)
    throw
}
[LauncherManifest]::Check([LauncherManifest]::EndUpdateResource($handle, $false))
