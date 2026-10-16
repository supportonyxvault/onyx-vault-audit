# Security Policy — ONYX VAULT Audit Repository

## 🔎 Was dieses Repo ist

Dies ist der **vollständige Code-Snapshot** von ONYX VAULT V1 (App + Website + Server-Konfig) für externe Security-Audits und Community-Reviews.

**Der Content ist redacted** — ohne `.onion`-Hostnamen, Public-IPs, Signing-Keys oder personenbezogene Daten. Alles was redacted ist, ist für die Code-Analyse irrelevant; es betrifft nur Infrastructure-Operations.

## 🛠️ Aktive Prüf-Kanäle (ehrlich dokumentiert)

### Automatisch bei jedem Push
- **GitHub CodeQL** (Kotlin + JavaScript/TypeScript) via `security-and-quality` Query-Suite
- **GitHub Secret Scanning** (sucht committed Keys/Tokens)
- **GitHub Push Protection** (blockt Secrets beim push)
- **Dependabot** (Vulnerability-Warnings für Gradle + Actions-Dependencies)

### Scheduled
- **CodeQL wöchentlich** — Montag 06:00 UTC

### Externes Review
- **AI-assisted Code Review** — LLM-basierter Audit-Chat hat den Code durchgearbeitet, Findings dokumentiert, bestätigte Lücken wurden behoben
- **Community-Audit** — AGPL-3.0 erlaubt jeder/m die Codebasis zu klonen, zu analysieren und Findings zu melden

### Was wir NICHT behaupten
- ❌ Keine Zertifizierung durch GitHub oder andere Dritte
- ❌ Kein kommerzielles externes Audit durch eine Security-Firma (noch nicht — ggf. später)
- ❌ Keine formelle Common-Criteria-/CSPN-/BSI-Zertifizierung

Transparenz heißt auch: wir werben nicht mit Dingen die wir nicht haben.

## 🐛 Responsible Disclosure

Wenn du eine Sicherheitslücke findest:
1. **Kein öffentliches GitHub-Issue** mit Lücken-Details
2. **Kontaktiere uns direkt** — Kontakt via Website [Impressum](https://supportonyxvault.github.io/onyxvault-website/impressum.html)
3. **Nenne:** Datei + Zeile + Reproduktions-Schritte + Impact
4. **Gib uns Zeit** zum Fix (Standard: 90 Tage)

Wir reagieren innerhalb 7 Tagen und dokumentieren bestätigte Fixes transparent.

## 🎯 Scope

Siehe [`SCOPE.txt`](./SCOPE.txt) für Priority-Files und Threat-Model.

## 📜 Lizenz

AGPL-3.0 — © 2026 ONYX VAULT Project
