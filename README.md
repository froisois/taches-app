# Tâches — application publiée

Ce dépôt public ne contient **que le programme**, jamais de données. Les tâches restent dans un fichier `taches.md` sur l’appareil de l’utilisateur ; la page le lit localement et n’envoie rien nulle part.

- **Sur ordinateur** (Edge) : tableau kanban complet, qui enregistre directement dans `taches.md`.
- **Sur téléphone, dans un navigateur** : vue de consultation ; le bouton « + » prépare un e-mail pour ajouter une tâche.
- **Dans l’appli Android « Tâches »** : même vue, modifiable, avec prise de notes rapide (bouton +, raccourci, menu Partager), qui enregistre directement dans `taches.md` (fusion avec les modifications faites ailleurs). APK : https://github.com/froisois/taches-app/releases/download/android/taches.apk

Adresse : https://froisois.github.io/taches-app/

Le développement, la documentation et l’historique des décisions vivent dans un dépôt privé distinct.

| Fichier | Rôle |
|---|---|
| `index.html` | l’application |
| `essai.html` | page d’essai sur téléphone (lecture d’un fichier OneDrive, e-mail préparé, partage vers OneDrive, mémoire locale) |
| `android/` | appli Android « Tâches » : coquille qui affiche cette page et lui donne accès au fichier choisi ; compilée par GitHub Actions (version « android ») |
| `android-essai/` | appli Android d’essai (écriture dans OneDrive), compilée par GitHub Actions et publiée dans la version « essai-android » |
