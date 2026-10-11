# Tâches — application publiée

Ce dépôt public ne contient **que le programme**, jamais de données. Les tâches restent dans un fichier `taches.md` sur l’appareil de l’utilisateur ; la page le lit localement et n’envoie rien nulle part.

- **Sur ordinateur** (Edge) : tableau kanban complet, qui enregistre directement dans `taches.md`.
- **Sur téléphone** : vue de consultation ; le bouton « + » prépare un e-mail pour ajouter une tâche.

Adresse : https://froisois.github.io/taches-app/

Le développement, la documentation et l’historique des décisions vivent dans un dépôt privé distinct.

| Fichier | Rôle |
|---|---|
| `index.html` | l’application |
| `essai.html` | page d’essai sur téléphone (lecture d’un fichier OneDrive, e-mail préparé, partage vers OneDrive, mémoire locale) |
| `android-essai/` | appli Android d’essai (écriture dans OneDrive), compilée par GitHub Actions et publiée dans la version « essai-android » |
