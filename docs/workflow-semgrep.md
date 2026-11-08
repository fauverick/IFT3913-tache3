# Modification du workflow GitHub Actions (Semgrep)

IFT 3913 — Tâche 3. Ce document décrit le gate Semgrep ajouté au dépôt, pas le CI Maven d’Apache Tika.

## Objectif

Le check GitHub **Semgrep** doit échouer lorsqu’un commit introduit une **nouvelle** vulnérabilité détectée par Semgrep dans les cinq modules imposés. Les findings déjà présents dans le snapshot Tika ne doivent pas faire échouer le build.

## Choix de conception

### Un workflow séparé

Le scan est dans [`.github/workflows/semgrep.yml`](../.github/workflows/semgrep.yml), pas dans `main-jdk21-build.yml`. Semgrep n’a pas besoin de JDK ni de Maven. Un job isolé donne un check rouge/vert lisible : si Semgrep échoue, ce n’est pas un test JUnit ou un timeout Windows.

Pas de Semgrep Cloud (`semgrep ci` + jeton). `semgrep scan` sur le runner suffit pour un projet de cours.

### Périmètre

Seuls les chemins listés dans l’énoncé sont scannés :

- `tika-core`
- `tika-serialization`
- `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-ocr-module`
- `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-pdf-module`
- `tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module`

Le reste du réacteur Maven n’est pas analysé.

### Nouvelle vs existante

`semgrep scan --config p/java --baseline-commit <sha> --error` ne rapporte que les findings apparus **après** le commit de base, puis sort avec le code 1 s’il y en a (`--error`).

- pull request : le SHA de base est `github.event.pull_request.base.sha` (`main`)
- push sur `main` : le SHA de base est `github.event.before` (commit précédent)

Si la base est absente ou vaut `0000…0000` (premier commit du dépôt), le job lance un scan **sans** `--error`. Sinon l’import du snapshot ferait échouer CI à cause de findings déjà dans Tika.

`actions/checkout` utilise `fetch-depth: 0` pour que le commit de base existe dans le clone.

### Règles

`--config p/java` (règles publiques Java de Semgrep). Pas de règles custom à ce stade. Si une vulnérabilité plantée plus tard n’est pas vue par `p/java`, on pourra ajouter une règle alors.

Le rapport JSON (`semgrep-report.json`) est publié en artefact GitHub Actions, y compris quand le job échoue.

## Validation

Procédure :

1. Pousser ce workflow sur `main` : le job doit être **vert** (aucun finding nouveau par rapport au commit précédent).
2. Ouvrir une branche jetable avec un appel `Runtime.getRuntime().exec(...)` dans `tika-core`, ouvrir une PR vers `main` : le job doit être **rouge** et le JSON doit lister la règle.
3. Ne pas fusionner la PR ; retirer la sonde.

### Résultats

*(À compléter après les runs Actions.)*

- Run vert (workflow seul, pas de finding nouveau) : _URL_
- Run rouge (sonde `Runtime.exec`) : _URL / PR_
- Extrait du JSON rouge : règle et fichier
