# Suno Inspector

App Android (Kotlin) : ouvre suno.com dans une WebView, détecte les morceaux de ta bibliothèque
(réponses JSON de l API du site) et télécharge les MP3 + paroles dans le dossier de ton choix.

## Utilisation
1. Ouvre l app, connecte-toi à Suno (si Google refuse, utilise la connexion par e-mail).
2. Ouvre ta bibliothèque (Library / Create) et fais défiler la liste jusqu en bas.
3. Bouton **Infos** : vérifie le nombre de morceaux détectés.
4. Bouton **Télécharger** : choisis un dossier (la 1re fois), puis le compte si plusieurs sont vus.
   Garde l app ouverte pendant le téléchargement. Début et fin estimée affichés en haut.
5. Chaque lancement crée un sous-dossier Suno_AAAA-MM-JJ_HH-MM. Les morceaux déjà téléchargés sont ignorés.
6. **Export JSON** : sauvegarde la liste détectée (nom horodaté).

Compilation : GitHub Actions (workflow Android APK) -> artefact Suno-Inspector-debug.
