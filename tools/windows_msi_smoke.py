"""Install, upgrade and uninstall two Ruleblend MSIs in a temporary Unicode path.

Usage: python tools/windows_msi_smoke.py PREVIOUS.msi CURRENT.msi
Requires native Windows and no existing Ruleblend MSI installation. Leaves logs and
isolated user data in the printed temporary directory; never deletes a user installation.
"""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import time

from mcp_smoke import verify


def msi_properties(path):
    msi = ctypes.WinDLL("msi")
    database, view, record = (wintypes.UINT() for _ in range(3))
    assert msi.MsiOpenDatabaseW(str(path), None, ctypes.byref(database)) == 0
    try:
        assert msi.MsiDatabaseOpenViewW(database, "SELECT `Property`, `Value` FROM `Property`",
                                       ctypes.byref(view)) == 0
        assert msi.MsiViewExecute(view, 0) == 0
        result = {}
        while True:
            status = msi.MsiViewFetch(view, ctypes.byref(record))
            if status == 259:
                break
            assert status == 0, status
            try:
                fields = []
                for index in (1, 2):
                    value = ctypes.create_unicode_buffer(32768)
                    length = wintypes.DWORD(len(value))
                    assert msi.MsiRecordGetStringW(record, index, value, ctypes.byref(length)) == 0
                    fields.append(value.value)
                result[fields[0]] = fields[1]
            finally:
                msi.MsiCloseHandle(record)
        return result
    finally:
        if view.value:
            msi.MsiCloseHandle(view)
        msi.MsiCloseHandle(database)


def related_products(upgrade):
    products = []
    msi = ctypes.WinDLL("msi")
    while True:
        product = ctypes.create_unicode_buffer(39)
        status = msi.MsiEnumRelatedProductsW(upgrade, 0, len(products), product)
        if status == 259:
            return products
        assert status == 0, status
        products.append(product.value)


def snapshot(home):
    return {str(path.relative_to(home)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in home.rglob("*") if path.is_file()}


def shortcuts():
    paths = []
    for folder, relative in ((0x10, "Ruleblend.lnk"), (0x02, "Ruleblend/Ruleblend.lnk")):
        path = ctypes.create_unicode_buffer(260)
        assert ctypes.windll.shell32.SHGetFolderPathW(None, folder, None, 0, path) == 0
        paths.append(Path(path.value) / relative)
    return paths


def gui_smoke(binary, root):
    home = root / "Пользователь MCP"
    env = dict(os.environ)
    for key in ("JAVA_HOME", "JDK_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                "RULEBLEND_LIBRARY"):
        env.pop(key, None)
    env.update(JAVA_TOOL_OPTIONS=f'-Duser.home="{home}"',
               PATH=str(Path(os.environ["SystemRoot"]) / "System32"),
               CLAUDE_CONFIG_DIR=str(home / ".claude"), CODEX_HOME=str(home / ".codex"),
               KIMI_CODE_HOME=str(home / ".kimi"), PI_CODING_AGENT_DIR=str(home / ".pi/agent"),
               GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=str(home / ".gitconfig"))
    user32 = ctypes.WinDLL("user32")
    kernel = ctypes.WinDLL("kernel32")
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                                 wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    user32.IsWindowVisible.argtypes = [wintypes.HWND]
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    windows = []

    @callback_type
    def inspect(handle, _):
        if user32.IsWindowVisible(handle):
            pid = wintypes.DWORD()
            user32.GetWindowThreadProcessId(handle, ctypes.byref(pid))
            process = kernel.OpenProcess(0x1000, False, pid.value)
            if process:
                try:
                    path = ctypes.create_unicode_buffer(32768)
                    size = wintypes.DWORD(len(path))
                    if kernel.QueryFullProcessImageNameW(process, 0, path, ctypes.byref(size)):
                        if Path(path.value) == binary:
                            windows.append(handle)
                finally:
                    kernel.CloseHandle(process)
        return True

    with (root / "gui.log").open("wb") as log:
        process = subprocess.Popen([str(binary)], env=env, stdout=log, stderr=log)
        try:
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                user32.EnumWindows(inspect, 0)
                if windows:
                    break
                assert process.poll() is None, "GUI exited before showing a window"
                time.sleep(0.2)
            assert windows, "GUI did not show a visible window"
            for window in windows:
                user32.PostMessageW(window, 0x0010, 0, 0)
            assert process.wait(timeout=20) == 0, "GUI did not exit cleanly"
        finally:
            if process.poll() is None:
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                               capture_output=True, check=False)
                process.wait(timeout=15)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("previous", type=Path)
    parser.add_argument("current", type=Path)
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("Native Windows is required")
    previous, current = args.previous.resolve(), args.current.resolve()
    old, new = msi_properties(previous), msi_properties(current)
    upgrade = new["UpgradeCode"]
    assert old["ProductName"] == new["ProductName"] == "Ruleblend"
    assert old["UpgradeCode"] == upgrade and old["ProductCode"] != new["ProductCode"]
    assert tuple(map(int, old["ProductVersion"].split('.'))) < tuple(map(int, new["ProductVersion"].split('.')))
    assert not old.get("ALLUSERS") and not new.get("ALLUSERS"), "MSIs must install per user"
    assert not related_products(upgrade), "An existing Ruleblend installation must not be changed"
    links = shortcuts()
    assert not any(path.exists() for path in links), "Existing Ruleblend shortcuts must not be changed"
    root = Path(tempfile.mkdtemp(prefix="ruleblend-msi-smoke-")).resolve()
    print(f"Logs and isolated user data: {root}", flush=True)
    installed = root / "Установка Ruleblend с пробелами"
    assert installed.is_relative_to(root)
    binary = installed / "Ruleblend.exe"

    def msiexec(*arguments, log):
        # MSI's property parser requires NAME="value with spaces", not "NAME=value with spaces".
        parts = [str(Path(os.environ["SystemRoot"]) / "System32/msiexec.exe"),
                 *arguments, "/qn", "/norestart", "/L*v", str(root / log)]
        command = " ".join(f'INSTALLDIR="{part.removeprefix("INSTALLDIR=")}"'
                           if part.startswith("INSTALLDIR=") else subprocess.list2cmdline([part])
                           for part in parts)
        result = subprocess.run(command, timeout=180)
        assert result.returncode == 0, f"MSI returned {result.returncode}; see {root / log}"

    try:
        msiexec("/i", str(previous), f"INSTALLDIR={installed}", log="install.log")
        assert binary.is_file() and (installed / "runtime/bin/server/jvm.dll").is_file()
        assert related_products(upgrade) == [old["ProductCode"]]
        assert all(path.is_file() for path in links), "Install did not create both shortcuts"
        verify(installed, root, copy_image=False)
        home = root / "Пользователь MCP"
        foreign = home / ".codex/config.toml"
        foreign.parent.mkdir(exist_ok=True)
        foreign.write_text('[mcp_servers.foreign]\ncommand = "keep unchanged"\n', encoding="utf-8")
        before = snapshot(home)
        msiexec("/i", str(current), f"INSTALLDIR={installed}", log="upgrade.log")
        assert related_products(upgrade) == [new["ProductCode"]], "Upgrade left duplicate installations"
        assert all(path.is_file() for path in links), "Upgrade removed shortcuts"
        assert snapshot(home) == before, "Upgrade changed user data"
        verify(installed, root, copy_image=False)
        gui_smoke(binary, root)
        before = snapshot(home)
        msiexec("/x", new["ProductCode"], log="uninstall.log")
        assert not binary.exists() and not related_products(upgrade)
        assert not any(path.exists() for path in links), "Uninstall left shortcuts"
        assert snapshot(home) == before, "Uninstall changed user data"
        print(f"PASS: MSI {old['ProductVersion']} -> {new['ProductVersion']}, GUI, MCP, bundled Java, data preservation, uninstall")
    finally:
        # Only products installed by this invocation may be removed.
        for product in related_products(upgrade):
            assert product in (old["ProductCode"], new["ProductCode"])
            msiexec("/x", product, log="cleanup.log")


if __name__ == "__main__":
    main()
