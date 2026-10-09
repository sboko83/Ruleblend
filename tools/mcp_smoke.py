"""Verify a Windows app image over MCP stdio in an isolated Unicode installation/home.

Usage: python tools/mcp_smoke.py app/build/compose/binaries/main/app/Ruleblend
"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import queue
import shutil
import stat
import struct
import subprocess
import tempfile
import threading


def verify(image: Path, root: Path, *, copy_image=True):
    installed = root / "Установка Ruleblend с пробелами" if copy_image else image
    if copy_image:
        shutil.copytree(image, installed)
    binary = installed / "Ruleblend.exe"
    data = binary.read_bytes()
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    assert struct.unpack_from("<H", data, pe + 24 + 68)[0] == 2, "Launcher must use the GUI subsystem"
    home = root / "Пользователь MCP"
    home.mkdir(exist_ok=True)
    env = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "RULEBLEND_LIBRARY"):
        env.pop(key, None)
    env["JAVA_TOOL_OPTIONS"] = f'-Duser.home="{home}"'
    env.pop("JAVA_HOME", None)
    env.pop("JDK_HOME", None)
    env["PATH"] = str(Path(os.environ["SystemRoot"]) / "System32")
    env["GIT_CONFIG_NOSYSTEM"] = "1"
    env["GIT_CONFIG_GLOBAL"] = str(home / ".gitconfig")
    # Give every supported assistant an isolated native home as well.
    env.update(CLAUDE_CONFIG_DIR=str(home / ".claude"), CODEX_HOME=str(home / ".codex"),
               KIMI_CODE_HOME=str(home / ".kimi"), PI_CODING_AGENT_DIR=str(home / ".pi/agent"))
    lines = queue.Queue()
    with (root / "stderr.log").open("wb") as stderr:
        process = subprocess.Popen([str(binary), "--mcp", "--library", str(home / "Библиотека правил")],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr, env=env)
        def read_stdout():
            for line in process.stdout:
                lines.put(line)
            lines.put(None)
        reader = threading.Thread(target=read_stdout, daemon=True)
        reader.start()
        def send(message):
            process.stdin.write((json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8"))
            process.stdin.flush()
        def response(identifier, *, error=False):
            raw = lines.get(timeout=30)
            assert raw is not None, "MCP exited without a response"
            value = json.loads(raw)
            assert value.get("id") == identifier and "error" not in value, value
            assert value["result"].get("isError", False) is error, value
            return value["result"]
        try:
            send(dict(jsonrpc="2.0", id=1, method="initialize", params=dict(
                protocolVersion="2024-11-05", capabilities={}, clientInfo=dict(name="smoke", version="1"))))
            assert response(1)["serverInfo"]["name"] == "ruleblend"
            send(dict(jsonrpc="2.0", method="notifications/initialized"))
            send(dict(jsonrpc="2.0", id=2, method="tools/call", params=dict(name="create_rule", arguments=dict(
                name="Smoke rule", content="Проверка stdio: кириллица и пробелы"))))
            response(2)
            send(dict(jsonrpc="2.0", id=3, method="tools/call", params=dict(name="get_rule", arguments=dict(id="smoke-rule"))))
            assert "Проверка stdio" in json.dumps(response(3), ensure_ascii=False)
            send(dict(jsonrpc="2.0", id=4, method="tools/list"))
            catalog = {tool["name"] for tool in response(4)["tools"]}
            assert {"export_library", "preview_archive_import", "get_archive_import_entry",
                    "apply_archive_import", "library_history", "library_diff",
                    "library_sync_status", "sync_library"} <= catalog, catalog
            def call(identifier, name, arguments):
                send(dict(jsonrpc="2.0", id=identifier, method="tools/call",
                          params=dict(name=name, arguments=arguments)))
                result = response(identifier)
                return json.loads(result["content"][0]["text"])
            history = call(5, "library_history", dict(kind="block", id="smoke-rule"))
            assert history, "Created rule has no library history"
            patch = call(6, "library_diff", dict(kind="block", id="smoke-rule", revision=history[0]["revision"]))
            assert "Проверка stdio" in patch["diff"]
            assert call(7, "library_sync_status", {})["configured"] is False
            zip_path = str(root / "Обмен библиотеки.zip")
            assert call(8, "export_library", dict(path=zip_path))["bytes"] > 0
            preview = call(9, "preview_archive_import", dict(path=zip_path))
            entry = call(10, "get_archive_import_entry", dict(preview_id=preview["preview_id"], kind="block", id="smoke-rule"))
            assert entry["change"] == "same"
            assert call(11, "apply_archive_import", dict(preview_id=preview["preview_id"], block_ids=["smoke-rule"]))["applied"]
            invalid_calls = [
                ("create_rule", dict(name=42, content="Invalid name")),
                ("update_rule", dict(id="smoke-rule", content="Must not be saved", heading_level="2")),
                ("install", dict(target=str(home), rule_id="smoke-rule", overwrite="true")),
            ]
            for identifier, (name, arguments) in enumerate(invalid_calls, start=12):
                send(dict(jsonrpc="2.0", id=identifier, method="tools/call",
                          params=dict(name=name, arguments=arguments)))
                assert response(identifier, error=True)["content"], "Missing argument validation error"
            unchanged = call(15, "get_rule", dict(id="smoke-rule"))
            assert unchanged["content"] == "Проверка stdio: кириллица и пробелы"
            # The GUI-subsystem launcher must not open an app window in MCP mode.
            windows = []
            kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_bool, ctypes.c_ulong]
            kernel.OpenProcess.restype = ctypes.c_void_p
            kernel.QueryFullProcessImageNameW.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_wchar_p,
                                                         ctypes.POINTER(ctypes.c_ulong)]
            kernel.CloseHandle.argtypes = [ctypes.c_void_p]
            callback_type = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
            @callback_type
            def inspect_window(handle, _):
                if not ctypes.windll.user32.IsWindowVisible(ctypes.c_void_p(handle)):
                    return True
                pid = ctypes.c_ulong()
                ctypes.windll.user32.GetWindowThreadProcessId(ctypes.c_void_p(handle), ctypes.byref(pid))
                if pid.value == process.pid:
                    windows.append(handle)
                    return True
                # jpackage may re-exec the same binary as a child before loading the JVM.
                child = kernel.OpenProcess(0x1000, False, pid.value)
                if child:
                    try:
                        path = ctypes.create_unicode_buffer(32768)
                        size = ctypes.c_ulong(len(path))
                        if kernel.QueryFullProcessImageNameW(child, 0, path, ctypes.byref(size)):
                            if path.value.casefold() == str(binary).casefold():
                                windows.append(handle)
                    finally:
                        kernel.CloseHandle(child)
                return True
            ctypes.windll.user32.EnumWindows(inspect_window, 0)
            assert not windows, "MCP opened a visible window"
            process.stdin.close()
            assert process.wait(timeout=15) == 0, "MCP failed on EOF"
            assert lines.get(timeout=5) is None, "Unexpected output after the last response"
            assert (home / ".ruleblend").is_dir(), "JVM did not honor the isolated Unicode home"
        except BaseException as failure:
            process.kill()
            process.wait(timeout=15)
            raise RuntimeError((root / "stderr.log").read_text(encoding="utf-8", errors="replace")) from failure
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=15)
            process.stdout.close()
            reader.join(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("This smoke check requires native Windows")
    root = Path(tempfile.mkdtemp(prefix="ruleblend-mcp-smoke-")).resolve()
    try:
        verify(args.image.resolve(), root)
        print("PASS: packaged MCP handshake, Unicode tool round trip, clean stdout, no window, EOF exit")
    finally:
        # Only remove the exact scratch root created above, never a supplied installation path.
        assert root.parent == Path(tempfile.gettempdir()).resolve() and root.name.startswith("ruleblend-mcp-smoke-")
        def remove_readonly(function, path, _):
            target = Path(path).resolve()
            assert target.is_relative_to(root)
            target.chmod(stat.S_IWRITE)
            function(path)
        shutil.rmtree(root, onexc=remove_readonly)


if __name__ == "__main__":
    main()
