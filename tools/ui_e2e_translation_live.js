// Native AX flow for the packaged app and its bundled macOS translation helper.
ObjC.import('Foundation');
ObjC.import('ApplicationServices');

function run(argv) {
    const pid = Number(argv[0]), artifacts = argv[1], caseId = argv[2], reverseInstalled = argv[3] === 'true';
    const system = Application('System Events');
    function write(name, value) {
        $(String(value)).writeToFileAtomicallyEncodingError(artifacts + '/' + name, true, $.NSUTF8StringEncoding, null);
    }
    function tree(element, depth) {
        if (depth > 12) return [];
        let rows = [element];
        try {
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
    function snapshot(window, label) {
        write(label + '.txt', tree(window, 0).map(e => {
            let value = '';
            try { if (e.role() === 'AXTextField') value = ' value=' + e.value(); } catch (_) {}
            return e.role() + ' ' + name(e) + value;
        }).join('\n'));
    }
    function waitFor(label, get) {
        write('step.txt', label);
        const deadline = Date.now() + 20000;
        while (Date.now() < deadline) {
            try { const value = get(); if (value) return value; }
            catch (error) { if (error.errorNumber !== -1719) throw error; }
            delay(0.15);
        }
        throw Error('Timed out: ' + label);
    }
    function find(window, labels) {
        const matches = tree(window, 0).filter(element => labels.includes(name(element)));
        return matches.length === 1 ? matches[0] : null;
    }
    function findRuleRow(window, title) {
        // The inspector repeats the selected title; only the catalog row includes kind and version.
        const matches = tree(window, 0).filter(element => name(element).startsWith('R, ' + title + ', v'));
        return matches.length === 1 ? matches[0] : null;
    }
    const process = system.applicationProcesses.whose({unixId: pid})[0];
    function activate() {
        if (process.unixId() !== pid) throw Error('Target PID changed');
        process.frontmost = true;
        if (!process.frontmost()) throw Error('Target did not receive focus');
    }
    let step = 'accessibility';
    let window;
    let result;
    const completed = [];
    try {
        if (!$.AXIsProcessTrusted()) throw Error('Accessibility permission unavailable for osascript');
        waitFor('application process', () => system.applicationProcesses.whose({unixId: pid}).length === 1);
        activate();
        window = waitFor('native window', () => process.windows.length === 1 ? process.windows[0] : null);
        completed.push('launch');
        snapshot(window, 'window-start');
        step = 'library';
        const library = waitFor('Library tab', () => find(window, ['Library', 'Библиотека']));
        library.actions.byName('AXPress').perform();
        waitFor('library rule', () => findRuleRow(window, 'Live Translation Rule'));
        snapshot(window, 'library');
        step = 'rule';
        waitFor('select rule', () => {
            const row = findRuleRow(window, 'Live Translation Rule');
            if (!row) return false;
            row.actions.byName('AXPress').perform();
            return true;
        });
        waitFor('rule edit', () => find(window, ['Edit', 'Править', 'Редактировать']));
        snapshot(window, 'inspector');
        waitFor('open rule editor', () => {
            const edit = find(window, ['Edit', 'Править', 'Редактировать']);
            if (!edit) return false;
            edit.actions.byName('AXPress').perform();
            return true;
        });
        const translate = waitFor('Translate control', () => find(window, ['Translate', 'Перевод']));
        snapshot(window, 'editor');
        completed.push('open-rule');
        step = 'translate';
        translate.actions.byName('AXPress').perform();
        const translated = waitFor('translated Russian sentence', () => {
            const text = tree(window, 0).map(name);
            // The source's own Russian paragraph is about a file; only a translation mentions saving.
            return text.find(value => /сохран/i.test(value) && !value.includes('Please save this rule.')) || null;
        });
        snapshot(window, 'translated');
        if (translated.includes('Please save this rule.')) throw Error('Translation did not change the source');
        // The source pane shows the flag too, so only a Cyrillic paragraph counts as translated.
        waitFor('protected flag in translated paragraph', () => tree(window, 0).some(e =>
            /[А-Яа-яЁё]/.test(name(e)) && name(e).includes('`--safe`') && !name(e).includes('Use `--safe` here.')));
        completed.push('translate');
        if (caseId === 'T08' && reverseInstalled) {
            step = 'reverse';
            waitFor('reverse direction action', () => {
                const swap = find(window, ['Swap', 'Направление']);
                if (!swap) return false;
                swap.actions.byName('AXPress').perform();
                return true;
            });
            waitFor('reverse direction header', () => find(window, ['Translation RU → EN', 'Перевод RU → EN']));
            const reverseText = waitFor('translated English sentence', () =>
                tree(window, 0).map(name).find(value => /file/i.test(value) && !/[А-Яа-яЁё]/.test(value)) || null);
            write('reverse.txt', reverseText);
            completed.push('reverse-translate');
        }
        if (caseId === 'T09') {
            step = 'repeat';
            const hide = waitFor('Hide translation control', () => find(window, ['Hide translation', 'Скрыть перевод']));
            hide.actions.byName('AXPress').perform();
            waitFor('Translate control again', () => find(window, ['Translate', 'Перевод']));
            find(window, ['Translate', 'Перевод']).actions.byName('AXPress').perform();
            waitFor('repeat translation', () => tree(window, 0).some(e => name(e) === translated));
            completed.push('repeat');
        }
        activate();
        system.keyCode(12, {using: ['command down']});
        waitFor('application closed', () => system.applicationProcesses.whose({unixId: pid}).length === 0);
        completed.push('close');
        result = {status: 'passed', completed: completed, translated: translated};
    } catch (error) {
        if (window) snapshot(window, 'failure-window');
        const prerequisite = String(error).includes('permission unavailable') || error.errorNumber === -1743;
        result = {status: prerequisite ? 'blocked' : 'failed', reason: String(error), step: step, completed: completed};
    }
    write('translation-live-result.json', JSON.stringify(result, null, 2));
    return JSON.stringify(result);
}
