#!/usr/bin/env python3
"""Profile selection fixture: a small Kotlin project and an isolated library of 30 items.

    profile_pick_fixture.py <root>     create <root>/project/orbit-api and <root>/home/.ruleblend/library
    profile_pick_fixture.py --expected print the expected profile composition with a reason per item

Nothing outside <root> is touched. Point Ruleblend and Claude Code at <root>/home through
`-Duser.home` (JAVA_TOOL_OPTIONS) and HOME; the library is written through the documented storage
layout (blocks/<id>.md, skills/<id>/meta.yaml + files/), never through a running Ruleblend.
"""
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

PROJECT_NAME = 'orbit-api'

# kind: rule | subagent | skill | mcp. Verdicts: include, overlap (loses to a sibling), foreign, unneeded.
# `reason` is the expected justification the assistant should arrive at.
ITEMS = [
    # Matching the project's stack
    ('rule', 'kotlin-style', 'Kotlin code style', 'include',
     'The only product language is Kotlin.'),
    ('rule', 'kotlin-coroutines', 'Kotlin coroutines', 'include',
     'Request handlers in src/main/kotlin are suspend functions.'),
    ('rule', 'ktor-routing', 'Ktor routing conventions', 'include',
     'The build file declares ktor-server dependencies.'),
    ('rule', 'gradle-kts-builds', 'Gradle Kotlin DSL builds', 'include',
     'The build is build.gradle.kts with settings.gradle.kts.'),
    ('rule', 'junit5-testing', 'JUnit 5 testing', 'include',
     'src/test uses kotlin.test on the JUnit 5 platform.'),
    ('rule', 'github-actions-ci', 'GitHub Actions CI', 'include',
     'CI is .github/workflows/ci.yml.'),
    ('subagent', 'kotlin-test-writer', 'Kotlin test writer', 'include',
     'The project has tests and a Kotlin-only code base.'),
    ('skill', 'gradle-troubleshooting', 'Gradle troubleshooting', 'include',
     'A Gradle build is the only build system.'),
    ('mcp', 'github', 'GitHub MCP', 'include',
     'CI and the remote live on GitHub.'),
    # Stack-agnostic generic items
    ('rule', 'conventional-commits', 'Conventional commits', 'include',
     'Generic and cheap; the project history needs one commit style.'),
    ('rule', 'secure-coding-basics', 'Secure coding basics', 'include',
     'Generic; the project is a network service.'),
    ('subagent', 'code-reviewer', 'Code reviewer', 'include',
     'Generic reviewer that works for any stack.'),
    # Overlapping pairs: the first of each pair is in the profile
    ('rule', 'kotlin-style-strict', 'Kotlin code style (strict)', 'overlap',
     'Duplicates kotlin-style with stricter limits; keep one.'),
    ('rule', 'junit4-testing', 'JUnit 4 testing', 'overlap',
     'Older variant of junit5-testing; the project is on JUnit 5.'),
    ('rule', 'spring-boot-conventions', 'Spring Boot conventions', 'overlap',
     'Another server framework; the project uses Ktor.'),
    ('rule', 'gitlab-ci-pipelines', 'GitLab CI pipelines', 'overlap',
     'Alternative to github-actions-ci; the project has no .gitlab-ci.yml.'),
    # Clearly foreign
    ('rule', 'python-pep8', 'Python PEP 8', 'foreign', 'No Python in the project.'),
    ('rule', 'django-patterns', 'Django patterns', 'foreign', 'No Python in the project.'),
    ('rule', 'react-hooks', 'React hooks', 'foreign', 'No JavaScript front end.'),
    ('rule', 'swift-concurrency', 'Swift concurrency', 'foreign', 'No Swift in the project.'),
    ('rule', 'rust-ownership', 'Rust ownership', 'foreign', 'No Rust in the project.'),
    ('rule', 'go-error-handling', 'Go error handling', 'foreign', 'No Go in the project.'),
    ('rule', 'terraform-modules', 'Terraform modules', 'foreign', 'No infrastructure code.'),
    ('subagent', 'python-debugger', 'Python debugger', 'foreign', 'No Python in the project.'),
    ('subagent', 'swift-ui-designer', 'SwiftUI designer', 'foreign', 'No Swift or UI in the project.'),
    ('subagent', 'db-migration-planner', 'Database migration planner', 'unneeded',
     'The project has no database.'),
    ('skill', 'pdf-forms', 'PDF forms', 'foreign', 'The project handles no documents.'),
    ('skill', 'xlsx-reports', 'Spreadsheet reports', 'foreign', 'The project handles no spreadsheets.'),
    ('mcp', 'postgres', 'PostgreSQL MCP', 'unneeded', 'The project has no database.'),
    ('mcp', 'figma', 'Figma MCP', 'foreign', 'The project has no design files.'),
]

PROJECT_FILES = {
    'settings.gradle.kts': 'rootProject.name = "orbit-api"\n',
    'build.gradle.kts': (
        'plugins {\n    kotlin("jvm") version "2.0.21"\n    application\n}\n\n'
        'repositories { mavenCentral() }\n\n'
        'dependencies {\n'
        '    implementation("io.ktor:ktor-server-core:3.0.0")\n'
        '    implementation("io.ktor:ktor-server-netty:3.0.0")\n'
        '    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")\n'
        '    testImplementation(kotlin("test-junit5"))\n}\n\n'
        'tasks.test { useJUnitPlatform() }\n'
        'application { mainClass.set("orbit.AppKt") }\n'),
    'README.md': '# orbit-api\n\nA small Ktor service that reports satellite passes.\n',
    'src/main/kotlin/orbit/App.kt': (
        'package orbit\n\nimport io.ktor.server.application.*\nimport io.ktor.server.engine.*\n'
        'import io.ktor.server.netty.*\nimport io.ktor.server.response.*\nimport io.ktor.server.routing.*\n\n'
        'fun main() {\n    embeddedServer(Netty, port = 8080) {\n        routing {\n'
        '            get("/passes") { call.respondText(nextPass()) }\n        }\n    }.start(wait = true)\n}\n\n'
        'suspend fun nextPass(): String = "ISS 21:40"\n'),
    'src/test/kotlin/orbit/AppTest.kt': (
        'package orbit\n\nimport kotlin.test.Test\nimport kotlin.test.assertEquals\n'
        'import kotlinx.coroutines.runBlocking\n\nclass AppTest {\n'
        '    @Test fun nextPassIsReported() = runBlocking { assertEquals("ISS 21:40", nextPass()) }\n}\n'),
    '.github/workflows/ci.yml': (
        'name: ci\non: [push, pull_request]\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n'
        '      - uses: actions/checkout@v4\n      - uses: actions/setup-java@v4\n'
        '        with: { distribution: temurin, java-version: 21 }\n      - run: ./gradlew build\n'),
}

MCP_BODIES = {
    'github': {'transport': 'stdio', 'command': 'npx', 'args': ['-y', '@modelcontextprotocol/server-github']},
    'postgres': {'transport': 'stdio', 'command': 'npx', 'args': ['-y', '@modelcontextprotocol/server-postgres']},
    'figma': {'transport': 'http', 'url': 'https://figma.example.test/mcp'},
}


def write_atomic(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temp = tempfile.mkstemp(dir=path.parent, prefix='.tmp-')
    with os.fdopen(handle, 'w', encoding='utf-8') as stream:
        stream.write(text)
    os.replace(temp, path)


def frontmatter(name, description, kind):
    return ('---\n' f'name: "{name}"\n' f'description: "{description}"\n' 'version: 1\n'
            f'type: "{kind}"\n' '---\n\n')


def description(item):
    return f'{item[2]} guidance for projects that need it.'


def write_item(library, item):
    kind, item_id, name, _verdict, _reason = item
    body = f'# {name}\n\nFollow the {name.lower()} conventions.\n'
    if kind == 'skill':
        directory = library / 'skills' / item_id
        write_atomic(directory / 'meta.yaml', f'name: "{item_id}"\n')
        write_atomic(directory / 'files' / 'SKILL.md',
                     f'---\nname: {item_id}\ndescription: {description(item)}\n---\n\n{body}')
    elif kind == 'mcp':
        write_atomic(library / 'blocks' / f'{item_id}.md',
                     frontmatter(name, description(item), 'mcp') + json.dumps(MCP_BODIES[item_id], indent=2))
    else:
        write_atomic(library / 'blocks' / f'{item_id}.md', frontmatter(name, description(item), kind) + body)


def check_catalog():
    ids = [item[1] for item in ITEMS]
    assert len(ids) == len(set(ids)), 'duplicate item ids'
    assert set(MCP_BODIES) == {item[1] for item in ITEMS if item[0] == 'mcp'}, 'MCP bodies out of sync'
    verdicts = {item[3] for item in ITEMS}
    assert verdicts <= {'include', 'overlap', 'foreign', 'unneeded'}, verdicts


def expected():
    lines = [f'Expected profile for {PROJECT_NAME}: '
             f'{sum(1 for item in ITEMS if item[3] == "include")} of {len(ITEMS)} items', '', 'Include:']
    lines += [f'  {item[0]:8} {item[1]:24} {item[4]}' for item in ITEMS if item[3] == 'include']
    lines += ['', 'Exclude:']
    lines += [f'  {item[0]:8} {item[1]:24} [{item[3]}] {item[4]}' for item in ITEMS if item[3] != 'include']
    return '\n'.join(lines)


def build(root):
    root = root.resolve()
    real_home = Path.home().resolve()
    if root == real_home or real_home.is_relative_to(root):
        sys.exit(f'refusing to use {root}: it contains the real home')
    if root.exists() and any(root.iterdir()):
        sys.exit(f'refusing to use {root}: not empty')
    library = root / 'home' / '.ruleblend' / 'library'
    for item in ITEMS:
        write_item(library, item)
    project = root / 'project' / PROJECT_NAME
    for relative, text in PROJECT_FILES.items():
        write_atomic(project / relative, text)
    # Ruleblend counts Claude Code as available when its home exists; without it no project agent
    # can be enabled and the profile cannot be installed.
    (root / 'home' / '.claude').mkdir()
    git = ['git', '-c', 'user.name=fixture', '-c', 'user.email=fixture@example.invalid']
    for directory in (library, project):
        env = {**os.environ, 'HOME': str(root / 'home'), 'GIT_CONFIG_GLOBAL': os.devnull}
        for step in (['init', '-q'], ['add', '-A'], ['commit', '-q', '-m', 'Fixture']):
            subprocess.run([*git, '-C', str(directory), *step], check=True, env=env)
    print(f'library: {library}')
    print(f'project: {project}')
    print(f'home:    {root / "home"}')


def main():
    check_catalog()
    args = sys.argv[1:]
    if args == ['--expected']:
        print(expected())
    elif len(args) == 1 and not args[0].startswith('-'):
        build(Path(args[0]))
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main()
