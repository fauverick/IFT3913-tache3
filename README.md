# IFT 3913 — Tâche 3
## Analyse de vulnérabilités avec Semgrep

### Équipe

| Membre | Matricule |
| :--- | :---: |
| **Hung Nguyen** | 20246446 |
| **Olivier Larue** | 20269986 |

Travaux réalisés pour la **Tâche 3** sur le projet [Apache Tika](https://github.com/apache/tika).

### Sommaire

1. [Workflow GitHub Actions (Semgrep)](#1-workflow-github-actions-semgrep)
2. [Vulnérabilités introduites](#2-vulnérabilités-introduites)
3. [Rickroll dans le CI](#3-rickroll-dans-le-ci)
4. [Usage de l'IA](#usage-de-lia)

## 1. Workflow GitHub Actions (Semgrep)

### Choix de conception

#### Un workflow séparé

Le scan est dans [`.github/workflows/semgrep.yml`](.github/workflows/semgrep.yml). Une job isolé donne un check rouge/vert lisible : si Semgrep échoue, ce n’est pas un test JUnit ou un timeout Windows.


#### Nouvelle vs existante

`semgrep scan --config p/java --config p/findsecbugs --baseline-commit <sha> --error` ne rapporte que les findings apparus **après** le commit de base, puis sort avec le code 1 s’il y en a.

- pull request : le SHA de base est `github.event.pull_request.base.sha` (`main`)
- push sur `main` : le SHA de base est `github.event.before` (commit précédent)

Si la base est absente ou vaut `0000…0000` (premier commit du dépôt), le job lance un scan **sans** `--error`. Sinon l’import du snapshot ferait échouer CI à cause de findings déjà dans Tika.

`actions/checkout` utilise `fetch-depth: 0` pour que le commit de base existe dans le clone.

#### Règles

`--config p/java` et `--config p/findsecbugs`. Pas de règles custom : les 9 vulnérabilités introduites (section 2) sont détectées par ces packs.

Le rapport JSON (`semgrep-report.json`) est publié en artefact GitHub Actions, y compris quand le job échoue.

### Validation

Procédure :

1. Pousser ce workflow sur `main` : le job doit être **vert**.
2. Ouvrir une branche jetable avec `MessageDigest.getInstance("MD5")` dans `tika-core`, ouvrir une PR vers `main` : le job doit être **rouge**. MD5 n’est plus sûr (collisions) ; la règle Java `p/java` le signale.
3. Ne pas fusionner la PR ; retirer la sonde.

#### Résultats

- Vert: [run GitHub Actions](https://github.com/fauverick/IFT3913-tache3/actions/runs/42431306324), artefact [semgrep-report/semgrep-report-main.json](rapport/semgrep-report/semgrep-report-main.json). Semgrep 1.184.0, `results: []`, `paths.scanned: []`. Le diff par rapport à `main` ne touche aucun Java des cinq modules, donc aucun finding nouveau ; le job reste vert.
- Rouge (sonde MD5 dans `Tika.java`) : [job GitHub Actions](https://github.com/fauverick/IFT3913-tache3/actions/runs/42432121880/job/128583103509), artefact [semgrep-report/semgrep-report-dummy-test.json](rapport/semgrep-report/semgrep-report-dummy-test.json).
- Extrait du JSON rouge : `java.lang.security.audit.crypto.use-of-md5.use-of-md5` dans `tika-core/src/main/java/org/apache/tika/Tika.java` (ligne 80, CWE-328). Semgrep flague MD5 parce que l’algorithme est connu pour ne pas être sécuritaire (cassé cryptographiquement: pas résistant aux collisions).

## 2. Vulnérabilités introduites

Règles : `p/java` + `p/findsecbugs`, Semgrep 1.185.0 (même configuration que le [workflow](#1-workflow-github-actions-semgrep)).

### 1. Hachage faible — MD5 (CWE-328)

- **Fichier** : `tika-parser-ocr-module/.../ocr/TesseractOCRParser.java`, méthode `imageFingerprint`.
- **Code** : `MessageDigest.getInstance("MD5")`.
- **Pourquoi vulnérable** : MD5 n'est pas résistant aux collisions. Deux images différentes peuvent avoir le même hash. Un attaquant pourrait faire passer le texte d'une image pour celui d'une autre.
- **Semgrep** : `use-of-md5`, ligne 815.

### 2. XXE (CWE-611)

- **Fichier** : `tika-parser-pdf-module/.../pdf/XFAExtractor.java`, méthode `parseXfaDom`. Les formulaires XFA sont du XML embarqué dans un PDF, donc contrôlé par l'auteur du document.
- **Code** : `DocumentBuilderFactory.newInstance()` puis `newDocumentBuilder().parse(xfaIs)`, sans désactiver les DOCTYPE.
- **Pourquoi vulnérable** : un PDF malveillant peut déclarer une entité externe (`<!ENTITY x SYSTEM "file:///etc/passwd">`) et faire lire des fichiers locaux ou émettre des requêtes réseau.
- **Semgrep** : `documentbuilderfactory-disallow-doctype-decl-missing`, ligne 335.

### 3. Injection SQL (CWE-89)

- **Fichier** : `tika-parser-microsoft-module/.../microsoft/JackcessExtractor.java`, méthode `countRows`. Cette classe lit des bases Access ; les noms de table viennent du fichier analysé.
- **Code** : `statement.executeQuery("SELECT COUNT(*) FROM " + tableName)`.
- **Pourquoi vulnérable** : le nom de table est concaténé dans la requête. Un texte quelconque ajouté dans le fichier modifie la requête exécutée et peut donc permettre d'executer des requêtes SQL dangereuse.
- **Semgrep** : `formatted-sql-string`, ligne 425.

### 4. Vecteur d'initialisation statique (CWE-329)

- **Fichier** : `tika-core/.../parser/CryptoParser.java`, méthode `createDefaultCipher`. C'est la classe de Tika qui déchiffre les documents.
- **Code** : `new IvParameterSpec(iv)` avec `iv = {0, 1, …, 15}` hardcoder.
- **Pourquoi vulnérable** : un IV doit être unique par message.
- **Semgrep** : `no-static-initialization-vector`, ligne 113.

### 5. Protocole SSL obsolète (CWE-326)

- **Fichier** : `tika-core/.../parser/NetworkParser.java`, méthode `legacySocketFactory`. Cette classe ouvre déjà des connexions vers un parseur distant.
- **Code** : `SSLContext.getInstance("SSLv3")`.
- **Pourquoi vulnérable** : SSLv3 est connu comme étant non sécuritaire. Un potentiel attaquant pourrait sniffer le réseau et déchiffrer le trafic.
- **Semgrep** : `weak-ssl-context`, ligne 177.

### 6. Chiffrement cassé — RC4 (CWE-327)

- **Fichier** : `tika-parser-microsoft-module/.../microsoft/OfficeParser.java`, méthode `decryptLegacyStream`. Les anciens formats Office utilisaient réellement RC4.
- **Code** : `Cipher.getInstance("RC4")`.
- **Pourquoi vulnérable** : RC4 a des biais statistiques connus. Un attaquant peut en prendre avantager pour retrouver le texe clair.
- **Semgrep** : `use-of-rc4`, ligne 398.

### 7. Clé RSA trop courte (CWE-326)

- **Fichier** : `tika-serialization/.../serialization/JsonMetadata.java`, méthode `generateSigningKeyPair`.
- **Code** : `KeyPairGenerator.getInstance("RSA")` puis `initialize(512)`.
- **Pourquoi vulnérable** : une clé RSA de 512 bits se factorise en quelques heures. Le minimum recommandé est 2048 bits.
- **Semgrep** : `use-of-weak-rsa-key`, ligne 136.

### 8. Injection de commande (CWE-78)

- **Fichier** : `tika-parser-ocr-module/.../ocr/OcrShellRunner.java`, appelé depuis `TesseractOCRParser.doOCR`.
- **Code** : `r.exec("/bin/sh -c tesseract" + language)` ; `language` vient de la config OCR.
- **Pourquoi vulnérable** : la chaîne est passée à un shell. Une valeur du type `eng; id` exécute une deuxième commande.
- **Semgrep** : `command-injection-formatted-runtime-call`, ligne 16.

### 9. Mot de passe en dur (CWE-259)

- **Fichier** : `tika-core/.../parser/SimplePasswordProvider.java`, constructeur sans argument.
- **Code** : `ks.load(null, "tika-admin".toCharArray())`.
- **Pourquoi vulnérable** : le secret est dans le code et dans git ; impossible à révoquer sans redeployer.
- **Semgrep** : `HARD_CODE_PASSWORD-1`, ligne 49.

### Comparaison avant / après

Même commande que le workflow : `semgrep scan --config p/java --config p/findsecbugs --baseline-commit <sha> --json`. Seuls les findings **nouveaux** par rapport à `main` apparaissent.

- Avant : [semgrep-report-avant.json](rapport/semgrep-report/semgrep-report-avant.json), `main`, **0** finding (`results: []`).
- Après : [semgrep-report-apres.json](rapport/semgrep-report/semgrep-report-apres.json), `ift3913/vulnerabilities`, **18** findings.

Les 18 hits correspondent aux 9 vulnérabilités : `p/java` et `p/findsecbugs` flaguent souvent le même sink (ex. SQLi, MD5, RC4). Aucun finding du snapshot Tika n’apparaît, grâce à `--baseline-commit`.

## 3. Rickroll dans le CI

Structure utilisée : [Architecture du workflow](https://www.youtube.com/watch?v=dQw4w9WgXcQ)

Implémentation : [Action GitHub modifiée](https://www.youtube.com/watch?v=dQw4w9WgXcQ)

Validation : [Run GitHub Actions en échec](https://www.youtube.com/watch?v=dQw4w9WgXcQ)

Résultat : [Capture du commentaire sur la PR](https://www.youtube.com/watch?v=dQw4w9WgXcQ)

<sub>Est-ce que je vous ai eu ? Désolés hehehe.
 Les vrais liens : workflow [`main-jdk21-build.yml`](.github/workflows/main-jdk21-build.yml) (étapes `Fetch`, `Patch` et `Rickroll`), action d'origine [TejasvOnly/random-rickroll](https://github.com/TejasvOnly/random-rickroll)</sub>

 Voir cette PR pour la preuve du RickRoll ! : [Never Gonna Give you Up](https://github.com/fauverick/IFT3913-tache3/pull/27)

## Usage de l'IA

Claude a été utilisé pour trouver et proposer plusieurs vulnérabilités. Toutefois, les membres de l’équipe prennent la décision finale quant aux vulnérabilités qui sont conservées et modifient le code final.
Il a aussi aidé à la rédaction de la documentation. Majoritairement pour former la structure du readme, mais aussi pour corriger les multiples erreurs de français que j'ai introduites !
