# Security policy

[Русская версия](docs/ru/SECURITY.md)

## Reporting a vulnerability

Email [s.bokonyaev@yandex.ru](mailto:s.bokonyaev@yandex.ru) with the subject `Ruleblend security`.
Use this private channel for vulnerabilities, including unintended writes outside managed regions,
unsafe archive or Git imports, and exposure of credentials or private configuration.

Include the version and build, operating system, affected operation, expected impact and minimal
reproduction using synthetic files. Remove tokens, personal paths, private repository URLs and
unrelated configuration. Do not send a real library or exploit anyone else's environment.

Do not publish exploit details or sensitive data in an issue or pull request while a report is being
assessed. Coordinate disclosure with the maintainer after a fix or mitigation is available.
The maintainer will assess the report and discuss next steps by email; there is no guaranteed
response or fix deadline.

## Supported builds

During alpha development, security fixes target the current development branch. Older builds may
require an upgrade; there are no long-term support branches.

Report ordinary bugs with the issue template. See [CONTRIBUTING](CONTRIBUTING.md) for development
checks and file-write guarantees.
