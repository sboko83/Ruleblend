// Phase 9 native window checks. The Python runner owns the app process and filesystem sandbox.
ObjC.import('Foundation');
ObjC.import('ApplicationServices');
ObjC.import('CoreGraphics');
ObjC.import('AppKit');
ObjC.bindFunction('CGPreflightScreenCaptureAccess', ['bool', []]);

function run(argv) {
    const pid = Number(argv[0]), mode = argv[1], stage = argv[2];
    const project = argv[3], artifacts = argv[4], ffmpeg = argv[5], expectedVersion = argv[6];
    const system = Application('System Events');
    const host = Application.currentApplication();
    host.includeStandardAdditions = true;
    const process = system.applicationProcesses.whose({unixId: pid})[0];
    const completed = [];
    let step = 'permissions';
    function write(name, value) {
        $(String(value)).writeToFileAtomicallyEncodingError(artifacts + '/' + name, true, $.NSUTF8StringEncoding, null);
    }
    function waitFor(label, get) {
        step = label;
        write('step.txt', label);
        const deadline = Date.now() + 15000;
        while (Date.now() < deadline) {
            try {
                const value = get();
                if (value) return value;
            } catch (error) {
                if (error.errorNumber !== -1719 && error.errorNumber !== -10000) throw error;
            }
            delay(0.15);
        }
        throw Error('Timed out: ' + label);
    }
    function tree(element, depth, includeFileRows = false) {
        if (depth > 12) return [];
        let rows = [element];
        try {
            // File listings are needed only when selecting the exported archive, not for controls.
            if (!includeFileRows && ['AXOutline', 'AXBrowser', 'AXTable'].includes(element.role())) return rows;
            const children = element.uiElements;
            const count = Math.min(children.length, 400);
            for (let index = 0; index < count; index++) rows = rows.concat(tree(children[index], depth + 1, includeFileRows));
        } catch (_) {}
        return rows;
    }
    function name(element) {
        try { return element.name() || element.description() || ''; } catch (_) { return ''; }
    }
    function find(container, labels) {
        const matches = tree(container, 0).filter(element => {
            try { return labels.includes(element.name()) || labels.includes(element.description()); }
            catch (_) { return false; }
        });
        const interactive = matches.filter(element => {
            try { return element.role() !== 'AXStaticText'; } catch (_) { return false; }
        });
        return interactive.length === 1 ? interactive[0] : matches.length === 1 ? matches[0] : null;
    }
    function activate() {
        if (process.unixId() !== pid) throw Error('Target PID changed');
        process.frontmost = true;
        waitFor('target focus', () => process.frontmost());
    }
    function window() {
        if (process.unixId() !== pid) throw Error('Target PID changed');
        return waitFor('one main window', () => process.windows.length === 1 ? process.windows[0] : null);
    }
    function press(container, labels) {
        waitFor('control ' + labels.join('/'), () => {
            const target = find(container, labels);
            if (!target) return false;
            try { target.actions.byName('AXPress').perform(); } catch (_) { target.click(); }
            return true;
        });
    }
    function pressContaining(container, fragment) {
        waitFor('control containing ' + fragment, () => {
            const matches = tree(container, 0).filter(element => name(element).includes(fragment));
            const target = matches.find(element => element.role() !== 'AXStaticText') || matches[0];
            if (!target) return false;
            try { target.actions.byName('AXPress').perform(); } catch (_) { target.click(); }
            return true;
        });
    }
    function dialog(title, retry) {
        let lastAttempt = 0;
        // Searching the full Compose tree can exhaust the dialog timeout before the opening click.
        // Start that timeout only after the first action has been dispatched.
        if (retry) {
            retry();
            lastAttempt = Date.now();
        }
        return waitFor('native dialog ' + title, () => {
            const named = process.windows.whose({name: title});
            if (named.length === 1) return named[0];
            const windows = [];
            for (let index = 0; index < process.windows.length; index++) {
                try { windows.push(process.windows[index]); } catch (_) {}
            }
            const chooser = windows.filter(item =>
                name(item) !== 'Ruleblend' && tree(item, 0).some(element =>
                    ['Cancel', 'Отмена'].includes(name(element))));
            if (chooser.length === 1) return chooser[0];
            if (retry && Date.now() - lastAttempt > 900) {
                lastAttempt = Date.now();
                retry();
            }
            return null;
        });
    }
    function cancelDialog(title) {
        const native = dialog(title);
        press(native, ['Cancel', 'Отмена']);
        waitFor('dialog cancelled', () => process.windows.whose({name: title}).length === 0);
    }
    function goTo(dialogWindow, path) {
        activate();
        system.keyCode(5, {using: ['command down', 'shift down']});
        const sheet = waitFor('Go to folder sheet', () => dialogWindow.sheets.length === 1 ? dialogWindow.sheets[0] : null);
        const field = waitFor('Go to folder field', () => {
            const fields = tree(sheet, 0).filter(element => element.role() === 'AXTextField');
            return fields.length === 1 ? fields[0] : null;
        });
        field.value = path;
        system.keyCode(36);
        waitFor('Go to folder dismissed', () => dialogWindow.sheets.length === 0);
    }
    function screenshot(main, name) {
        if (!$.CGPreflightScreenCaptureAccess()) throw Error('Screen recording permission unavailable for osascript');
        const rect = main.position().concat(main.size()).map(value => Math.round(Number(value)));
        if (rect.length !== 4 || rect.some(value => !Number.isFinite(value))) throw Error('Invalid native geometry');
        write('window-geometry.json', JSON.stringify(rect));
        const display = $.NSScreen.screens.objectAtIndex(0).frame.size;
        const [x, y, width, height] = rect;
        if (x < 0 || y < 0 || x + width > display.width || y + height > display.height) {
            throw Error('Window must fit on primary display for native capture');
        }
        const crop = 'crop=iw*' + width / display.width + ':ih*' + height / display.height +
            ':iw*' + x / display.width + ':ih*' + y / display.height;
        const command = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin', '-f', 'avfoundation',
            '-pixel_format', 'bgr0', '-capture_cursor', '0', '-framerate', '1', '-i', 'Capture screen 0:none',
            '-vf', crop, '-frames:v', '1', '-update', '1', '-y', artifacts + '/' + name + '.png'];
        activate();
        host.doShellScript(command.map(value => "'" + String(value).replace(/'/g, "'\\''") + "'").join(' '));
    }
    function closeApp() {
        activate();
        system.keyCode(12, {using: ['command down']});
        waitFor('application closed', () => system.applicationProcesses.whose({unixId: pid}).length === 0);
        completed.push('close');
    }

    let result;
    try {
        if (!$.AXIsProcessTrusted()) throw Error('Accessibility permission unavailable for osascript');
        waitFor('application process', () => system.applicationProcesses.whose({unixId: pid}).length === 1);
        activate();
        const main = window();
        write('target-pid.txt', String(pid));
        write('accessibility.txt', tree(main, 0).map(element => element.role() + ' ' + name(element)).join('\n'));
        completed.push('launch');

        if (mode === 'N08') {
            if (stage === 'first') {
                press(main, ['Targets', 'Проекты']);
                press(main, ['Add project', 'Добавить проект', '+ Add project', '+ Добавить проект']);
                dialog('Add project');
                cancelDialog('Add project');
                if (tree(main, 0).some(element => name(element) === 'Проект A')) throw Error('Cancelled folder was added');
                completed.push('folder-cancel');
                press(main, ['Add project', 'Добавить проект', '+ Add project', '+ Добавить проект']);
                const folder = dialog('Add project');
                goTo(folder, project);
                press(folder, ['Open', 'Choose', 'Открыть', 'Выбрать']);
                waitFor('Unicode project visible', () => tree(main, 0).some(element => name(element) === 'Проект A'));
                completed.push('folder-select');
                activate();
                system.keyCode(43, {using: ['command down']});
                press(main, ['Settings: Library', 'Настройки: Библиотека']);
                dialog('Import library', () => press(main, ['Import', 'Импорт']));
                cancelDialog('Import library');
                completed.push('zip-cancel');
                press(main, ['Export', 'Экспорт']);
                const save = dialog('Export library');
                goTo(save, artifacts + '/../../archives');
                press(save, ['Save', 'Сохранить']);
                const exported = artifacts + '/archives.zip';
                waitFor('exported ZIP', () => $.NSFileManager.defaultManager.fileExistsAtPath(exported));
                write('exported-zip.txt', exported);
                completed.push('zip-export');
                press(main, ['OK', 'ОК']);
                waitFor('export message dismissed', () => !tree(main, 0).some(element =>
                    ['Exported', 'Экспортировано'].includes(name(element))));
            } else {
                press(main, ['Settings', 'Настройки']);
                press(main, ['Settings: Library', 'Настройки: Библиотека']);
                const open = dialog('Import library', () => press(main, ['Import', 'Импорт']));
                goTo(open, artifacts.slice(0, -'restart'.length) + 'first');
                const archive = waitFor('archive row', () => {
                    const matches = tree(open, 0, true).filter(element => element.role() === 'AXRow' &&
                        tree(element, 0).some(child => child.role() === 'AXTextField' &&
                            String(child.value()) === 'archives.zip'));
                    return matches.length === 1 ? matches[0] : null;
                });
                try { archive.selected = true; } catch (_) { archive.click(); }
                waitFor('archive selected', () => archive.selected() === true);
                press(open, ['Open', 'Открыть']);
                waitFor('ZIP import result', () => tree(main, 0).some(element =>
                    ['Nothing to import', 'Нечего импортировать'].includes(name(element))));
                completed.push('zip-select');
                const display = $.NSScreen.screens.objectAtIndex(0).frame.size;
                const size = main.size().map(Number), position = main.position().map(Number);
                main.position = [Math.max(0, Math.min(position[0], Number(display.width) - size[0] - 4)),
                    Math.max(0, Math.min(position[1], Number(display.height) - size[1] - 4))];
                screenshot(main, 'native-choosers');
            }
            closeApp();
        } else if (mode === 'N09') {
            const size = main.size().map(Number);
            if (size[0] < 900 || size[1] < 600) throw Error('Saved undersized frame was not clamped');
            main.size = [900, 600];
            waitFor('minimum window', () => {
                const actual = main.size().map(Number);
                return actual[0] >= 900 && actual[1] >= 600;
            });
            press(main, ['Targets', 'Проекты']);
            waitFor('long project label', () => tree(main, 0).some(element =>
                name(element).includes('Очень длинное название проекта')));
            pressContaining(main, 'Очень длинное название проекта');
            waitFor('project file tabs', () => tree(main, 0).some(element => name(element).startsWith('Skills')));
            press(main, ['Add project', 'Добавить проект', '+ Add project', '+ Добавить проект']);
            dialog('Add project');
            screenshot(main, 'narrow-with-dialog');
            cancelDialog('Add project');
            screenshot(main, 'narrow-place');
            const skillTabs = tree(main, 0).filter(element => name(element).startsWith('Skills') &&
                name(element).includes('/'));
            if (skillTabs.length !== 1) throw Error('Project Skills tab is not unique: ' + skillTabs.length);
            // Tabs are looked up afresh each time: a recomposition replaces the AX element.
            const tab = prefix => tree(main, 0).find(element => element.role() === 'AXRadioButton' &&
                name(element).startsWith(prefix + ','));
            // AppKit announces a radio-button tab's selection through its AXValue.
            const selectedState = element => { try { return Number(element.value()); } catch (_) { return NaN; } };
            const skillTab = skillTabs[0];
            const rulesTab = tab('Rules');
            write('skills-tab.txt', [skillTab.role(), name(skillTab), selectedState(skillTab),
                rulesTab ? name(rulesTab) + ' ' + selectedState(rulesTab) : 'no Rules tab'].join('\n'));
            // Compose maps Role.Tab to a page tab, which AppKit exposes as a radio button.
            if (skillTab.role() !== 'AXRadioButton') throw Error('Project Skills tab role is ' + skillTab.role());
            if (selectedState(skillTab) !== 0) throw Error('Unselected Skills tab reports selection');
            if (!rulesTab || selectedState(rulesTab) !== 1) throw Error('Selected Rules tab does not report selection');
            skillTab.actions.byName('AXPress').perform();
            waitFor('Skills tab reports selection', () => {
                const skills = tab('Skills'), rules = tab('Rules');
                return skills && rules && selectedState(skills) === 1 && selectedState(rules) === 0;
            });
            press(main, ['Coverage', 'Покрытие']);
            // Reach the object explicitly instead of relying on an expanded library group below the fold.
            press(main, ['Expand "Rules"', 'Развернуть «Правила»']);
            write('coverage-accessibility.txt', tree(main, 0).map(element => element.role() + ' ' + name(element)).join('\n'));
            waitFor('modified coverage cell', () => tree(main, 0).some(element => {
                const label = name(element).toLowerCase();
                return (label.includes('modified') && label.includes('remove from here')) ||
                    (label.includes('изменено') && label.includes('убрать отсюда'));
            }));
            screenshot(main, 'narrow-coverage');
            completed.push('minimum-layout');
            closeApp();
        } else if (mode === 'N10') {
            if (stage === 'first') {
                main.position = [50, 60];
                main.size = [900, 600];
                waitFor('chosen frame', () => {
                    const rect = main.position().concat(main.size()).map(Number);
                    return Math.abs(rect[0] - 50) < 5 && Math.abs(rect[1] - 60) < 5 &&
                        rect[2] >= 900 && rect[3] >= 600;
                });
            }
            activate();
            system.keyCode(43, {using: ['command down']});
            press(main, ['Settings: About', 'Настройки: О программе']);
            waitFor('About version', () => tree(main, 0).some(element => name(element).startsWith('Ruleblend ' + expectedVersion)));
            screenshot(main, 'about');
            completed.push('about-and-frame');
            closeApp();
        } else if (mode === 'N11-prepare') {
            press(main, ['Library', 'Библиотека']);
            completed.push('library-open');
            Application('Finder').activate();
            waitFor('UI backgrounded', () => !process.frontmost());
            result = {status: 'passed', completed: completed};
        } else if (mode === 'N11-verify') {
            activate();
            waitFor('MCP rule after focus', () => tree(main, 0).some(element =>
                name(element).includes('native-mcp-rule') || name(element).includes('Native MCP rule')));
            screenshot(main, 'mcp-after-focus');
            completed.push('focus-refresh');
            closeApp();
        } else {
            throw Error('Unknown native scenario: ' + mode);
        }
        result = result || {status: 'passed', completed: completed};
    } catch (error) {
        const blocked = String(error).includes('permission unavailable') || error.errorNumber === -1743;
        result = {status: blocked ? 'blocked' : 'failed', reason: String(error), errorNumber: error.errorNumber,
            step: step, completed: completed};
    }
    write(mode + '-result.json', JSON.stringify(result, null, 2));
    return JSON.stringify(result);
}
