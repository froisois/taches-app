package fr.froisois.taches;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.app.PendingIntent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.provider.Settings;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coquille de l'outil Tâches : affiche la page publiée (vue téléphone) et lui prête l'accès
 * au fichier taches.md choisi dans OneDrive, par le pont JavaScript « TachesAndroid ».
 *
 * Contrat du pont : request(id, méthode, argument) ; la réponse revient par
 * window.tachesAndroidReply(id, ok, valeur). Méthodes : name, pick, read, write.
 */
public class MainActivity extends Activity {
    private static final String HOTE = "froisois.github.io";
    private static final String CHEMIN = "/taches-app/";
    private static final String DEPART = "https://" + HOTE + CHEMIN + "?vue=mobile";
    private static final String PAGE = "https://" + HOTE + CHEMIN + "index.html";
    private static final int CHOISIR = 1;
    private static final int CHOISIR_DOSSIER = 4;
    private static final int ENREGISTRER_SOUS = 5;
    private static final String PJ = "Pièces jointes";
    /** Fichier reçu par le menu Partager, copié dans le cache en attendant d'être rangé. */
    private File partage = null;
    private String partageNom = null, partageType = null;
    private boolean partageALivrer = false;
    private int pjEnCours = -1;
    private String pjNom = null;

    private WebView web;
    private SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService reseau = Executors.newSingleThreadExecutor();
    private static final String DEPOT = "https://github.com/froisois/taches-app/releases/download/android/";
    private static final String FIN_INSTALLATION = "fr.froisois.taches.INSTALLATION";
    private int majEnCours = -1;
    private volatile boolean pageAutorisee = false;
    private int choixEnCours = -1;
    private static final String NOTE = "fr.froisois.taches.NOTE";
    /** Note à ouvrir dans la page (raccourci ou partage), en attendant qu'elle soit chargée ; null = rien. */
    private String noteEnAttente = null;
    private boolean pageChargee = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("taches", MODE_PRIVATE);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Pont(), "TachesAndroid");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new Client());
        setContentView(web);
        if (b == null) lireIntention(getIntent()); // pas après un changement de thème
        try { ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(MajRecue.NOTIF); } catch (Exception ignored) { }
        finInstallation(getIntent());
        web.loadUrl(DEPART);
    }

    // ---------- navigation : seule notre page reste dans l'appli ----------

    private static boolean notre(Uri u) {
        return u != null && "https".equals(u.getScheme()) && HOTE.equals(u.getHost())
                && u.getPath() != null && u.getPath().startsWith(CHEMIN);
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (notre(u)) return false;
            ouvrirAilleurs(u);
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
            pageAutorisee = notre(Uri.parse(url));
            pageChargee = false;
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            pageChargee = notre(Uri.parse(url));
            livrerNote();
        }

        /** La page elle-même : version en ligne si possible (gardée en copie), sinon dernière copie, sinon celle embarquée. */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (!r.isForMainFrame() || !notre(u) || !"GET".equals(r.getMethod())) return null;
            String p = u.getPath();
            if (!CHEMIN.equals(p) && !(CHEMIN + "index.html").equals(p)) return null;
            byte[] page = pageEnLigne();
            if (page == null) page = copie();
            if (page == null) return null;
            WebResourceResponse rep = new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(page));
            rep.setResponseHeaders(Collections.singletonMap("Cache-Control", "no-store"));
            return rep;
        }
    }

    private void ouvrirAilleurs(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Aucune appli pour ouvrir ce lien", Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean pageValide(byte[] b) {
        if (b == null || b.length < 1000) return false;
        String t = new String(b, StandardCharsets.UTF_8);
        return t.contains("tachesAndroidReply") && t.contains("</html>");
    }

    private byte[] pageEnLigne() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(PAGE).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return null;
            byte[] b;
            try (InputStream in = c.getInputStream()) { b = tout(in); }
            if (!pageValide(b)) return null;
            try (OutputStream o = new FileOutputStream(new File(getFilesDir(), "index.html"))) { o.write(b); }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] copie() {
        try (InputStream in = new java.io.FileInputStream(new File(getFilesDir(), "index.html"))) {
            byte[] b = tout(in);
            if (pageValide(b)) return b;
        } catch (Exception ignored) { }
        try (InputStream in = getAssets().open("index.html")) {
            return tout(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] tout(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    // ---------- pont JavaScript ----------

    private class Pont {
        @JavascriptInterface
        public void request(final int id, final String methode, final String arg) {
            if (!pageAutorisee) { repondre(id, false, "page non autorisée"); return; }
            switch (methode) {
                case "name":
                    io.execute(() -> repondre(id, true, fichier() == null ? "" : prefs.getString("nom", "taches.md")));
                    break;
                case "pick":
                    runOnUiThread(() -> choisir(id));
                    break;
                case "read":
                    io.execute(() -> {
                        try {
                            Uri u = exige();
                            // « frais » (tirer pour actualiser) : demander à OneDrive sa dernière version avant de lire
                            if ("frais".equals(arg)) { try { getContentResolver().refresh(u, null, null); } catch (Exception ignored) { } }
                            repondre(id, true, lire(u));
                        }
                        catch (Exception e) { repondre(id, false, message(e)); }
                    });
                    break;
                case "write":
                    io.execute(() -> {
                        try { repondre(id, true, ecrire(exige(), arg) ? "" : "non vérifié"); }
                        catch (Exception e) { repondre(id, false, message(e)); }
                    });
                    break;
                case "saveAttachment":
                    rangerPieceJointe(id, arg);
                    break;
                case "saveAttachmentAs":
                    rangerSous(id, arg);
                    break;
                case "openAttachment":
                    ouvrirPieceJointe(id, arg);
                    break;
                case "discardFile":
                    partage = null; partageNom = null;
                    repondre(id, true, "");
                    break;
                case "version":
                    repondre(id, true, String.valueOf(versionInstallee()));
                    break;
                case "checkUpdate":
                    reseau.execute(() -> {
                        try { repondre(id, true, new String(telecharger(DEPOT + "version.txt", 100), StandardCharsets.UTF_8).trim()); }
                        catch (Exception e) { repondre(id, false, message(e)); }
                    });
                    break;
                case "update":
                    runOnUiThread(() -> mettreAJour(id));
                    break;
                default:
                    repondre(id, false, "méthode inconnue : " + methode);
            }
        }
    }

    private void repondre(int id, boolean ok, String valeur) {
        final String js = "window.tachesAndroidReply&&tachesAndroidReply(" + id + "," + ok + "," + JSONObject.quote(valeur == null ? "" : valeur) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    // ---------- fichier ----------

    /** Le fichier mémorisé, s'il a encore son autorisation de lecture et d'écriture. */
    private Uri fichier() {
        String s = prefs.getString("uri", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isReadPermission() && p.isWritePermission()) return u;
        return null;
    }

    private Uri exige() throws Exception {
        Uri u = fichier();
        if (u == null) throw new Exception("autorisation perdue : choisis à nouveau le fichier");
        return u;
    }

    private String lire(Uri u) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("fichier illisible");
            String t = new String(tout(in), StandardCharsets.UTF_8);
            return t.startsWith("﻿") ? t.substring(1) : t;
        }
    }

    /**
     * Remplace le contenu (mode « wt » : tronqué puis écrit), puis relit pour vérifier.
     * OneDrive peut renvoyer un moment l'ancienne version : on réessaie pendant quelques secondes.
     * Une erreur d'écriture lève une exception ; une relecture encore différente renvoie seulement false.
     */
    private boolean ecrire(Uri u, String texte) throws Exception {
        byte[] octets = texte.getBytes(StandardCharsets.UTF_8);
        try (OutputStream o = getContentResolver().openOutputStream(u, "wt")) {
            if (o == null) throw new Exception("écriture impossible");
            o.write(octets);
            o.flush();
        }
        for (int essai = 0; essai < 6; essai++) {
            if (lire(u).equals(texte)) return true;
            Thread.sleep(500);
        }
        return false;
    }

    private void choisir(int id) {
        if (choixEnCours != -1) repondre(choixEnCours, false, "annulé");
        choixEnCours = id;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, CHOISIR);
        } catch (Exception e) {
            choixEnCours = -1;
            repondre(id, false, "sélecteur de fichiers indisponible");
        }
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code == CHOISIR_DOSSIER || code == ENREGISTRER_SOUS) { resultatPieceJointe(code, res, data); return; }
        if (code != CHOISIR) return;
        final int id = choixEnCours;
        choixEnCours = -1;
        if (id == -1) return;
        if (res != RESULT_OK || data == null || data.getData() == null) { repondre(id, false, "annulé"); return; }
        final Uri u = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            repondre(id, false, "ce fichier ne peut pas être modifié par l'appli (" + message(e) + ")");
            return;
        }
        io.execute(() -> {
            // on ne garde que l'autorisation du fichier choisi
            String tree = prefs.getString("tree", null);
            for (UriPermission p : getContentResolver().getPersistedUriPermissions())
                if (!p.getUri().equals(u) && !p.getUri().toString().equals(tree)) {
                    try { getContentResolver().releasePersistableUriPermission(p.getUri(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
                    catch (Exception ignored) { }
                }
            String nom = "taches.md";
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) nom = c.getString(0);
            } catch (Exception ignored) { }
            prefs.edit().putString("uri", u.toString()).putString("nom", nom).apply();
            repondre(id, true, nom);
        });
    }

    // ---------- mise à jour de l'appli depuis la version publiée sur GitHub ----------

    private long versionInstallee() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static byte[] telecharger(String adresse, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(adresse).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new Exception("réponse " + c.getResponseCode() + " de GitHub");
        try (InputStream in = c.getInputStream()) {
            byte[] b = tout(in);
            if (b.length > max) throw new Exception("fichier trop gros");
            return b;
        }
    }

    private int majApresPermission = -1;

    /** Avant la première mise à jour : demander le droit d'afficher une notification (Android 13 et plus), puis continuer. */
    private void mettreAJour(int id) {
        if (majEnCours != -1) { repondre(id, false, "mise à jour déjà en cours"); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("notifDemandee", false)) {
            prefs.edit().putBoolean("notifDemandee", true).apply();
            majApresPermission = id;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
            return;
        }
        installer(id);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 3 && majApresPermission != -1) { int id = majApresPermission; majApresPermission = -1; installer(id); }
    }

    private void installer(int id) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            repondre(id, false, "autorise d'abord « Tâches » à installer des applis, puis touche à nouveau « Installer »");
            return;
        }
        majEnCours = id;
        prefs.edit().putBoolean("majDemandee", true).commit();
        reseau.execute(() -> {
            try {
                byte[] apk = telecharger(DEPOT + "taches.apk", 30 * 1024 * 1024);
                if (apk.length < 4 || apk[0] != 'P' || apk[1] != 'K') throw new Exception("fichier téléchargé invalide");
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(getPackageName());
                if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int sid = pi.createSession(sp);
                try (PackageInstaller.Session s = pi.openSession(sid)) {
                    try (OutputStream o = s.openWrite("taches.apk", 0, apk.length)) {
                        o.write(apk);
                        s.fsync(o);
                    }
                    Intent retour = new Intent(this, MainActivity.class).setAction(FIN_INSTALLATION);
                    int drap = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pe = PendingIntent.getActivity(this, 7, retour, drap);
                    s.commit(pe.getIntentSender());
                }
            } catch (Exception e) {
                int i = majEnCours;
                majEnCours = -1;
                prefs.edit().putBoolean("majDemandee", false).apply();
                repondre(i, false, message(e));
            }
        });
    }

    /** Résultat de l'installation, renvoyé par Android. */
    private boolean finInstallation(Intent i) {
        if (i == null || !FIN_INSTALLATION.equals(i.getAction())) return false;
        int statut = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (statut == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmer = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmer != null) {
                confirmer.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(confirmer); } catch (Exception ignored) { }
            }
            return true;
        }
        int id = majEnCours;
        majEnCours = -1;
        if (statut != PackageInstaller.STATUS_SUCCESS) prefs.edit().putBoolean("majDemandee", false).apply();
        if (statut == PackageInstaller.STATUS_SUCCESS) { if (id != -1) repondre(id, true, ""); }
        else {
            String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (id != -1) repondre(id, false, statut == PackageInstaller.STATUS_FAILURE_ABORTED ? "annulé" : (m == null ? "échec de l'installation" : m));
        }
        return true;
    }

    // ---------- raccourci « Nouvelle note » et menu Partager ----------

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (finInstallation(i)) return;
        setIntent(i);
        lireIntention(i);
        livrerNote();
    }

    private void lireIntention(Intent i) {
        if (i == null) return;
        if (NOTE.equals(i.getAction())) {
            noteEnAttente = "";
        } else if (Intent.ACTION_SEND.equals(i.getAction()) && i.getParcelableExtra(Intent.EXTRA_STREAM) != null) {
            recevoirFichier((Uri) i.getParcelableExtra(Intent.EXTRA_STREAM), i.getType());
        } else if (Intent.ACTION_SEND.equals(i.getAction()) && "text/plain".equals(i.getType())) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String sujet = i.getStringExtra(Intent.EXTRA_SUBJECT);
            String texte = t == null ? "" : t.toString().trim();
            if (sujet != null && !sujet.trim().isEmpty() && !texte.startsWith(sujet.trim())) texte = sujet.trim() + "\n" + texte;
            noteEnAttente = texte;
        }
    }

    private void livrerNote() {
        livrerFichier();
        if (noteEnAttente == null || !pageChargee || web == null) return;
        String js = "window.tachesAndroidNewNote&&tachesAndroidNewNote(" + JSONObject.quote(noteEnAttente) + ")";
        noteEnAttente = null;
        web.evaluateJavascript(js, null);
    }

    private void resultatPieceJointe(int code, int res, Intent data) {
        final int id = pjEnCours;
        pjEnCours = -1;
        if (id == -1) return;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            repondre(id, false, code == CHOISIR_DOSSIER ? "dossier-refuse" : "annulé");
            return;
        }
        final Uri u = data.getData();
        if (code == ENREGISTRER_SOUS) {
            io.execute(() -> {
                try { copierVers(u); repondre(id, true, PJ + "/" + nomDocument(u, propre(pjNom))); }
                catch (Exception e) { repondre(id, false, message(e)); }
            });
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            repondre(id, false, "dossier-refuse");
            return;
        }
        io.execute(() -> {
            // vérifier qu'il s'agit bien du dossier de taches.md
            boolean ok = false;
            String nomFichier = prefs.getString("nom", "taches.md");
            try {
                for (String[] e : enfants(u, DocumentsContract.getTreeDocumentId(u))) if (nomFichier.equals(e[1])) { ok = true; break; }
            } catch (Exception ignored) { }
            if (!ok) {
                try { getContentResolver().releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) { }
                repondre(id, false, "ce dossier ne contient pas " + nomFichier + " : choisis le dossier où il se trouve");
                return;
            }
            prefs.edit().putString("tree", u.toString()).apply();
            runOnUiThread(() -> rangerPieceJointe(id, pjNom));
        });
    }

    // ---------- fichier partagé vers l'appli : copié dans « Pièces jointes », à côté de taches.md ----------

    private void recevoirFichier(Uri u, String type) {
        String nom = "fichier";
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) nom = c.getString(0);
        } catch (Exception ignored) { }
        final String n = nom;
        partageNom = null;
        io.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(u)) {
                if (in == null) throw new Exception("fichier illisible");
                File dir = new File(getCacheDir(), "partage");
                dir.mkdirs();
                for (File f : dir.listFiles()) f.delete();
                File f = new File(dir, "recu");
                try (OutputStream o = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536]; int k; long total = 0;
                    while ((k = in.read(buf)) > 0) { o.write(buf, 0, k); total += k; if (total > 60L * 1024 * 1024) throw new Exception("fichier trop gros (60 Mo au plus)"); }
                }
                partage = f; partageNom = n; partageType = type == null || type.isEmpty() ? "application/octet-stream" : type;
                partageALivrer = true;
                runOnUiThread(this::livrerFichier);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Fichier non reçu : " + message(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void livrerFichier() {
        if (!partageALivrer || !pageChargee || web == null || partage == null) return;
        partageALivrer = false;
        try {
            JSONObject o = new JSONObject();
            o.put("name", partageNom); o.put("size", partage.length()); o.put("type", partageType);
            web.evaluateJavascript("window.tachesAndroidFile&&tachesAndroidFile(" + o + ")", null);
        } catch (Exception ignored) { }
    }

    private static String propre(String nom) {
        String n = nom == null ? "" : nom.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return n.isEmpty() ? "fichier" : n;
    }

    private Uri dossier() {
        String s = prefs.getString("tree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        return null;
    }

    /** Enfants d'un dossier : {identifiant, nom, type}. */
    private java.util.List<String[]> enfants(Uri tree, String parentId) {
        java.util.List<String[]> l = new java.util.ArrayList<>();
        Uri q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = getContentResolver().query(q, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) l.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
        }
        return l;
    }

    private String sousDossier(Uri tree, boolean creer) throws Exception {
        String racine = DocumentsContract.getTreeDocumentId(tree);
        for (String[] e : enfants(tree, racine))
            if (PJ.equals(e[1]) && DocumentsContract.Document.MIME_TYPE_DIR.equals(e[2])) return e[0];
        if (!creer) return null;
        Uri d = DocumentsContract.createDocument(getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(tree, racine),
                DocumentsContract.Document.MIME_TYPE_DIR, PJ);
        if (d == null) throw new Exception("impossible de créer le dossier « " + PJ + " »");
        return DocumentsContract.getDocumentId(d);
    }

    private String nomDocument(Uri u, String defaut) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) { }
        return defaut;
    }

    private void copierVers(Uri doc) throws Exception {
        try (InputStream in = new java.io.FileInputStream(partage); OutputStream o = getContentResolver().openOutputStream(doc, "w")) {
            if (o == null) throw new Exception("écriture impossible");
            byte[] buf = new byte[65536]; int k;
            while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
        }
    }

    /** Range le fichier reçu dans « Pièces jointes » ; renvoie le chemin relatif au dossier de taches.md. */
    private void rangerPieceJointe(int id, String nom) {
        if (partage == null) { repondre(id, false, "aucun fichier reçu"); return; }
        Uri tree = dossier();
        if (tree == null) {
            // première fois : demander l'accès au dossier de taches.md
            pjEnCours = id; pjNom = nom;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                Uri f = fichier();
                if (f != null && Build.VERSION.SDK_INT >= 26) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, f);
                try { startActivityForResult(i, CHOISIR_DOSSIER); }
                catch (Exception e) { pjEnCours = -1; repondre(id, false, "dossier-refuse"); }
            });
            return;
        }
        io.execute(() -> {
            try {
                String sous = sousDossier(tree, true);
                java.util.Set<String> pris = new java.util.HashSet<>();
                for (String[] e : enfants(tree, sous)) pris.add(e[1]);
                String n = propre(nom), base = n, ext = "";
                int pt = n.lastIndexOf('.');
                if (pt > 0) { base = n.substring(0, pt); ext = n.substring(pt); }
                for (int k = 2; pris.contains(n); k++) n = base + " (" + k + ")" + ext;
                Uri doc = DocumentsContract.createDocument(getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(tree, sous), partageType, n);
                if (doc == null) throw new Exception("création du fichier refusée");
                copierVers(doc);
                repondre(id, true, PJ + "/" + nomDocument(doc, n));
            } catch (Exception e) {
                repondre(id, false, message(e));
            }
        });
    }

    /** Solution de repli si OneDrive refuse l'accès au dossier : « Enregistrer sous » à chaque fichier. */
    private void rangerSous(int id, String nom) {
        if (partage == null) { repondre(id, false, "aucun fichier reçu"); return; }
        pjEnCours = id; pjNom = nom;
        runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(partageType);
            i.putExtra(Intent.EXTRA_TITLE, propre(nom));
            try { startActivityForResult(i, ENREGISTRER_SOUS); }
            catch (Exception e) { pjEnCours = -1; repondre(id, false, message(e)); }
        });
    }

    private void ouvrirPieceJointe(int id, String rel) {
        Uri tree = dossier();
        if (tree == null) { repondre(id, false, "l'appli n'a pas encore accès au dossier de taches.md"); return; }
        io.execute(() -> {
            try {
                String[] parts = rel.split("/");
                String cur = DocumentsContract.getTreeDocumentId(tree), type = null;
                for (String part : parts) {
                    String trouve = null;
                    for (String[] e : enfants(tree, cur)) if (part.equals(e[1])) { trouve = e[0]; type = e[2]; break; }
                    if (trouve == null) throw new Exception("fichier introuvable : " + rel);
                    cur = trouve;
                }
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, cur);
                final String t = type;
                runOnUiThread(() -> {
                    Intent v = new Intent(Intent.ACTION_VIEW);
                    v.setDataAndType(doc, t == null ? "*/*" : t);
                    v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(Intent.createChooser(v, null)); repondre(id, true, ""); }
                    catch (Exception e) { repondre(id, false, "aucune appli pour ouvrir ce fichier"); }
                });
            } catch (Exception e) {
                repondre(id, false, message(e));
            }
        });
    }

    // ---------- cycle de vie ----------

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.tachesAndroidResume&&tachesAndroidResume()", null);
    }

    @Override
    protected void onPause() {
        if (web != null) web.evaluateJavascript("window.tachesAndroidPause&&tachesAndroidPause()", null);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.tachesAndroidBack?tachesAndroidBack():false", v -> {
            if ("true".equals(v)) return;
            if (web.canGoBack()) web.goBack();
            else finish();
        });
    }
}
