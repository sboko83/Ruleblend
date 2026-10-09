// Reproducible macOS AX prototype. No coordinates, no permission prompts, bounded state waits.
ObjC.import('Foundation');
ObjC.import('ApplicationServices');
ObjC.import('CoreGraphics');
ObjC.import('AppKit');
ObjC.bindFunction('CGPreflightScreenCaptureAccess', ['bool', []]);
function run(argv) {
    const pid = Number(argv[0]), project = argv[1], artifacts = argv[2], ffmpeg = argv[3];
    const system = Application('System Events');
    const host = Application.currentApplication();
    host.includeStandardAdditions = true;
    function write(name, value) {
        $(value).writeToFileAtomicallyEncodingError(artifacts + '/' + name, true, $.NSUTF8StringEncoding, null);
    }
    function waitFor(label, get) {
        write('step.txt', label);
        const deadline = Date.now() + 12000;
        while (Date.now() < deadline) {
            try {
                const value = get();
                if (value) return value;
            } catch (error) {
                // Compose replaces native AX nodes during recomposition; re-resolve only that race.
                if (error.errorNumber !== -1719 && error.errorNumber !== -10000) throw error;
            }
            delay(0.15);
        }
        throw Error('Timed out: ' + label);
    }
    function tree(element, depth) {
        if (depth > 12) return [];
        let rows = [element];
        try {
            // Native file listings can contain thousands of entries; only controls are relevant.
            if (['AXOutline', 'AXBrowser', 'AXTable'].includes(element.role())) return rows;
            const children = element.uiElements;
            const count = children.length;
            for (let index = 0; index < count; index++) rows = rows.concat(tree(children[index], depth + 1));
        } catch (_) {}
        return rows;
    }
    function name(element) {
        try { return element.name() || element.description() || ''; } catch (_) { return ''; }
    }
    function find(window, labels) {
        const matches = tree(window, 0).filter(element => labels.includes(name(element)));
        // Ambiguous AX labels must be resolved by the driver, never by selecting the first match.
        return matches.length === 1 ? matches[0] : null;
    }
    // Keep filtered/indexed specifiers. Materializing objects with collection() loses the PID
    // in System Events and can resolve a same-named production application instead.
    const process = system.applicationProcesses.whose({unixId: pid})[0];
    function windows() {
        if (process.unixId() !== pid) throw Error('Target PID changed');
        return Array.from({length: process.windows.length}, (_, index) => process.windows[index]);
    }
    function activate() {
        if (process.unixId() !== pid) throw Error('Target PID changed');
        process.frontmost = true;
        if (!process.frontmost()) throw Error('Target did not receive focus');
    }
    const completed = [];
    let step = 'accessibility-permission';
    let result;
    try {
        if (!$.AXIsProcessTrusted()) throw Error('Accessibility permission unavailable for osascript');
        step = 'application-process';
        waitFor('application process', () => system.applicationProcesses.whose({unixId: pid}).length === 1);
        activate();
        const window = waitFor('native window', () => windows().length === 1 ? process.windows[0] : null);
        step = 'navigate';
        write('target-pid.txt', String(process.unixId()));
        completed.push('launch');
        write('accessibility.txt', tree(window, 0).map(e => e.role() + ' ' + name(e)).join('\n'));
        const place = waitFor('Projects tab', () => find(window, ['Targets', 'Проекты']));
        place.actions.byName('AXPress').perform();
        const add = waitFor('Add project action', () => find(window, ['Add project', 'Добавить проект', '+ Add project', '+ Добавить проект']));
        add.actions.byName('AXPress').perform();
        const dialog = waitFor('native folder dialog', () =>
            process.windows.whose({name: 'Add project'}).length === 1 ? process.windows.whose({name: 'Add project'})[0] : null);
        completed.push('folder-dialog');
        write('folder-before.txt', tree(dialog, 0).map(e => e.role() + ' ' + name(e)).join('\n'));
        step = 'choose-folder';
        activate();
        system.keyCode(5, {using: ['command down', 'shift down']});
        const goTo = waitFor('Go to folder sheet', () => dialog.sheets.length === 1 ? dialog.sheets[0] : null);
        const pathField = waitFor('Go to folder field', () => {
            const fields = tree(goTo, 0).filter(e => e.role() === 'AXTextField');
            return fields.length === 1 ? fields[0] : null;
        });
        pathField.value = project;
        system.keyCode(36);
        waitFor('Go to folder dismissed', () => dialog.sheets.length === 0);
        write('folder-controls.txt', tree(dialog, 0).map(e => e.role() + ' ' + name(e)).join('\n'));
        const choose = waitFor('folder confirmation', () => {
            return find(dialog, ['Open', 'Choose', 'Открыть', 'Выбрать']);
        });
        choose.actions.byName('AXPress').perform();
        waitFor('project visible', () => tree(window, 0).some(e => name(e) === 'Проект A'));
        completed.push('choose-folder');
        write('project-controls.txt', tree(window, 0).map(e => e.role() + ' ' + name(e)).join('\n'));
        step = 'keyboard-input';
        const filter = tree(window, 0).filter(e => e.role() === 'AXTextField' &&
            ['Filter targets', 'Filter targets…', 'Filter targets...', 'Фильтр проектов'].includes(name(e)));
        if (filter.length !== 1) throw Error('Project filter has no unique AX selector');
        activate();
        filter[0].click();
        // A digit tests real keyboard delivery without depending on the user's RU/EN layout.
        system.keyCode(18);
        waitFor('keyboard input', () => {
            const fields = tree(window, 0).filter(e => e.role() === 'AXTextField')
                .map(e => ({name: name(e), value: String(e.value())}));
            write('input-state.json', JSON.stringify(fields));
            return fields.some(e => e.value === '1');
        });
        completed.push('keyboard-input');
        step = 'screenshot';
        if (!$.CGPreflightScreenCaptureAccess()) throw Error('Screen recording permission unavailable for osascript');
        // System Events windows expose geometry, not the Quartz window number.
        const rect = window.position().concat(window.size()).map(n => Math.round(Number(n)));
        write('window-geometry.json', JSON.stringify(rect));
        if (rect.length !== 4 || rect.some(n => !Number.isFinite(n))) throw Error('Invalid native window geometry');
        const display = $.NSScreen.screens.objectAtIndex(0).frame.size;
        const [x, y, width, height] = rect;
        if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > display.width || y + height > display.height) {
            throw Error('Native window must fit on the primary display for this prototype');
        }
        // AX geometry is in points; AVFoundation delivers pixels. Ratios also handle Retina displays.
        const crop = 'crop=iw*' + width / display.width + ':ih*' + height / display.height +
            ':iw*' + x / display.width + ':ih*' + y / display.height;
        const command = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin', '-f', 'avfoundation',
            '-pixel_format', 'bgr0', '-capture_cursor', '0', '-framerate', '1', '-i', 'Capture screen 0:none',
            '-vf', crop, '-frames:v', '1', '-update', '1', '-y', artifacts + '/desktop.png'];
        activate();
        host.doShellScript(command.map(value => "'" + String(value).replace(/'/g, "'\\''") + "'").join(' '));
        completed.push('screenshot');
        step = 'close';
        activate();
        system.keyCode(12, {using: ['command down']});
        waitFor('application closed', () => system.applicationProcesses.whose({unixId: pid}).length === 0);
        completed.push('close');
        result = {status: 'passed', completed: completed};
    } catch (error) {
        const prerequisite = String(error).includes('permission unavailable') || error.errorNumber === -1743;
        result = {status: prerequisite ? 'blocked' : 'failed', reason: String(error), errorNumber: error.errorNumber, step: step, completed: completed,
                  manual: ['Any native action absent from completed needs manual verification']};
    }
    write('desktop-result.json', JSON.stringify(result, null, 2));
    return JSON.stringify(result);
}
