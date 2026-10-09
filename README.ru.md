# Ruleblend

Библиотека и «конструктор» блоков инструкций для AI-агентов.

## Что это

Ruleblend организует локальную библиотеку правил-инструкций, конфигов MCP-серверов, субагентов
и локальных или импортированных из Git скиллов для кодинг-агентов (Claude Code, Codex, Pi, Kimi Code, ZCode). Правила и MCP-конфиги
он встраивает в настройки агентов или папки проектов: правила как managed-регионы в `CLAUDE.md` /
`AGENTS.md`, MCP-серверы как записи в собственном MCP-конфиге каждого агента. Сам он тоже работает как
MCP-сервер, так что агенты могут искать по библиотеке и устанавливать правила — в проект или
глобальный файл агента — без ручного редактирования файлов инструкций. При подключении агента
Ruleblend также устанавливает встроенный skill с описанием этого workflow. Pi 1.0+ поддерживает MCP из коробки; расширение не требуется.

## Зачем

- Чистые агенты: в каждом проекте только нужные инструкции → меньше токенов, лучше контекст.
- Единый интерфейс для всех агентов: не нужно разбираться в консольных командах и форматах каждого.
- Workflow конструктора: собрал группы блоков, меняешь их по проектам на лету.

## Основные понятия

- **Блок** — именованное версионируемое правило или MCP-конфиг с описанием. У правила есть изменяемая
  область: глобальная или один проект, поэтому проектные правила не попадают в посторонние списки.
  Типы: `rule` (markdown-инструкция) и `mcp` (конфиг MCP-сервера, редактируется формой).
- **Skill** — полный каталог с `SKILL.md`, созданный локально или импортированный из Git-репозитория
  плагина Codex или Claude вместе со scripts, references и assets. Git-версия обновляется на месте, а редактирование
  начинается в отдельной `changed`-копии.
- **Субагент** — инструкции с отдельными полями для каждого ассистента, созданные локально или
  импортированные из Git: Codex TOML либо Claude Code/Kimi Code Markdown. Импорт предлагает
  предпросмотр и выбор исходного ассистента; обновление сохраняет id, редактирование начинается
  в отдельной изменённой копии.
- **Группа** — именованный упорядоченный набор правил, MCP-серверов, субагентов и скиллов
  (например, «iOS-проекты»).
- **Профиль** — переносимый режим проекта с объектами и плоскими группами; привязывается к проекту
  и включается или выключается там. Локальные привязки отделены от переносимого определения.
- **Проект** (раздел «Проекты») — папка проекта или глобальный конфиг агента, куда ставятся правила. Правила записываются managed-регионами:

```markdown
<!-- Ruleblend-managed. Do not edit below; changes are overwritten. -->
<!-- rb1 a1b2c3d4 git-no-commit@3:23:g=ios-projects swift-style@1:20 -->
...текст инструкции...
...текст инструкции...
<!-- rb:end -->
```

Одна строка манифеста описывает весь managed-run. Состояние выводится парсингом маркеров — отдельной базы установок нет. Обычное обновление правил сохраняет содержимое вне маркеров. MCP-записи, полные каталоги
установленных скиллов и отдельные определения субагентов не несут маркеров; владение отслеживается в sidecar-файлах.

Старые форматы `kb` и `kb1` продолжают читаться и при следующем изменении managed-контента
перезаписываются как `rb1`. `rb1` использует режим `partial` рядом с рукописным текстом или `owned`,
если Ruleblend рендерит весь файл. Источник истины остаётся в файле; изменение wrapped-run
помечается до записи. Для каждого файла можно включить предупреждение или отказаться от владения,
сохранив весь его текст.

## Стек

- Kotlin Multiplatform, Gradle 9.7 (современная структура KMP-модулей, JVM-цели)
- `core/` — чистый Kotlin/JVM: модели, хранилище (файлы + JGit), парсер маркеров, адаптеры агентов
- `mcp/` — фасад над `core`: stdio MCP-сервер, коннекторы MCP-конфигов агентов, установщики MCP-блоков
- `app/` — Compose Multiplatform Desktop (macOS и Windows); `--mcp` запускает сервер
- Хранилище библиотеки: `~/.ruleblend/library/` — markdown-файлы в git-репозитории; каждое сохранение — коммит

## Инструменты MCP

`Ruleblend --mcp` предоставляет инструменты для библиотеки:

- Правила: `list_rules`, `get_rule`, `create_rule`, `update_rule`, `delete_rule`.
- MCP-конфиги: `list_mcp_servers`, `get_mcp_server`, `create_mcp_server`,
  `update_mcp_server`, `delete_mcp_server`.
- Группы: `list_groups`, `get_group`, `create_group`, `update_group`, `delete_group`, `reorder_group`.
- Профили: `list_profiles`, `get_profile`, `create_profile`, `update_profile`, `delete_profile`.
- Субагенты: `list_subagents`, `get_subagent`, `create_subagent`, `update_subagent`,
  `delete_subagent`, `fork_subagent`.
- Skills: `list_skills`, `get_skill`, `create_skill`, `update_skill`, `delete_skill`, `fork_skill`.
- Файлы скиллов: `list_skill_files`, `read_skill_file`, `write_skill_file`, `delete_skill_file`.
- Git-импорт: `preview_git_import`, `get_git_import_entry`, `apply_git_import`.
- Git-источники: `list_sources`, `check_sources`, `update_from_sources`.
- Цели: `list_targets`, `install`, `uninstall`, `target_status`.
- Профили проектов: `get_project_profiles`, `attach_profile`, `set_profile_active`.
- Проекты и ассистенты: `register_project`, `unregister_project`, `set_project_agents`,
  `list_assistants`, `set_assistant_visibility`.
- Установленные копии: `list_target_entries`, `read_target_entry`, `save_target_entry`,
  `accept_local_change`, `manage_target_entry`, `reorder_target_rules`, `check_target_conflicts`.
- Обмен ZIP: `export_library`, `preview_archive_import`, `get_archive_import_entry`, `apply_archive_import`.
- История и Git: `library_history`, `library_diff`, `library_sync_status`, `sync_library`.

Обновление группы или профиля принимает карты `add` и `remove` с массивами id
по типам: `rule_ids`, `mcp_ids`, `subagent_ids`, `skill_ids`, а у профиля ещё `group_ids`.
При чтении MCP-конфига значения env и headers маскируются; для обновления передайте новые значения.
Флаг `--library /absolute/path` или `RULEBLEND_LIBRARY` задаёт отдельную библиотеку в режиме MCP.
Установленные копии остаются в целях до удаления или сверки.

## Лицензия

Исходный код и собственная графика Ruleblend распространяются по
[PolyForm Noncommercial 1.0.0](LICENSE).
Коммерческая лицензия: [s.bokonyaev@yandex.ru](mailto:s.bokonyaev@yandex.ru).
Сторонние компоненты сохраняют свои лицензии: [NOTICE](NOTICE), [THIRD-PARTY.md](THIRD-PARTY.md).
В установленных пакетах эти файлы находятся в `resources/legal/` приложения;
Java сохраняет собственный каталог `legal/`.

## Статус

Рабочее macOS-приложение — библиотека, импорт и обновление skills из Git, интеграция с пятью
агентами, перенос существующих правил, MCP-блоки, экспорт/импорт, двусторонняя Git-синхронизация и MCP-сервер уже
работают. Windows MSI доступен.
Сборки Linux запланированы.

## Установка неподписанной альфы

Скачайте DMG или MSI для своей ОС и архитектуры из артефактов релиза. Java включена в пакет.
Перед обновлением закройте Ruleblend и активные MCP-сессии. Рядом доступны соответствующие
исходники Java и `SHA256SUMS`; контрольные суммы выявляют повреждение загрузки и не являются
цифровой подписью.

- **macOS:** откройте DMG, перетащите Ruleblend в «Программы» и запустите. Альфа не подписана
  и не нотарифицирована. При блокировке откройте «Системные настройки» → «Конфиденциальность
  и безопасность» → «Всё равно открыть» для Ruleblend и подтвердите. Применяйте исключение
  только к доверенной загрузке; см. [инструкцию Apple](https://support.apple.com/ru-ru/102445).
- **Windows x64:** запустите MSI для установки текущему пользователю. Если SmartScreen сообщает
  о неизвестном приложении и предлагает исключение, для доверенной загрузки выберите
  «Подробнее» → «Выполнить в любом случае». Smart App Control или политика организации могут
  блокировать неподписанные пакеты без возможности исключения;
  см. [пояснение Microsoft](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation).
  Установка, обновление и удаление описаны ниже.

## Установщик Windows

Офлайн-перевод требует macOS 26.4+; его элементы управления скрыты на Windows и Linux.
Окну нужна рабочая область не менее 900 × 600 логических пикселей с учётом масштаба экрана.

Сборка на Windows x64 с JDK 21 для Gradle:

```powershell
.\gradlew.bat :app:packageMsi
```

MSI находится в `app/build/compose/binaries/main/msi/`. При первой сборке Gradle скачивает WiX.
Запустите MSI для установки текущему пользователю; отдельная Java не нужна. Перед обновлением
закройте Ruleblend и активные MCP-сессии Ruleblend. Удаление — через «Установленные приложения»
Windows. Идентичность установки и сохранение данных описаны в
[архитектуре](docs/ru/ARCHITECTURE.md#windows-distribution).

Пакеты не подписаны. Windows ARM64 и WSL не проверены как целевые окружения дистрибутива.

## Разработка на Windows в Android Studio

1. Откройте корень репозитория и дождитесь завершения Gradle Sync. Для Gradle выберите JDK 21
   в **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM**.
   Gradle daemon проекта работает на
   JDK 21, а приложение собирается и запускается на JDK 17; Gradle автоматически скачает
   JDK 17, если его нет на компьютере.
2. В списке конфигураций запуска выберите **Ruleblend (Windows)**. Если её нет в списке,
   переоткройте проект. Общая конфигурация находится в `.run/Ruleblend (Windows).run.xml`.
3. Поставьте breakpoint в Kotlin-коде и нажмите **Debug** (Shift+F9). Для обычного запуска
   используйте **Run** (Shift+F10). Конфигурация выполняет `:app:run` и подключает отладчик
   к приложению.

Первая синхронизация и сборка требуют интернета для загрузки зависимостей и JDK.
Запускается desktop-окно; Android-эмулятор и Windows-установщик не нужны.

Для запуска из терминала задайте `JAVA_HOME`, указывающий на JDK 21, и выполните:

```powershell
.\gradlew.bat :app:run
```

## UI-проверки по запросу

Запуск и настройка сквозных проверок — в [docs/ru/UI_E2E.md](docs/ru/UI_E2E.md).
Они запускаются вручную и не входят в обычные проверки и упаковку; нативным наборам
нужны собранное приложение macOS и отдельный запуск.

Участие в разработке: [CONTRIBUTING](docs/ru/CONTRIBUTING.md).
Сообщения об уязвимостях: [SECURITY](docs/ru/SECURITY.md).

Индекс документации: [docs/ru/README.md](docs/ru/README.md).

Устройство: [docs/ru/ARCHITECTURE.md](docs/ru/ARCHITECTURE.md), открытые задачи: [docs/ru/ROADMAP.md](docs/ru/ROADMAP.md), история релизов: [CHANGELOG.md](CHANGELOG.md).

English version: [README.md](README.md)
