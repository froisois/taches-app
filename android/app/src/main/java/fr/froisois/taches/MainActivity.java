package fr.froisois.taches;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
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

    private WebView web;
    private SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
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
                        try { repondre(id, true, lire(exige())); }
                        catch (Exception e) { repondre(id, false, message(e)); }
                    });
                    break;
                case "write":
                    io.execute(() -> {
                        try { ecrire(exige(), arg); repondre(id, true, ""); }
                        catch (Exception e) { repondre(id, false, message(e)); }
                    });
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

    /** Remplace le contenu (mode « wt » : tronqué puis écrit), puis relit pour vérifier. */
    private void ecrire(Uri u, String texte) throws Exception {
        byte[] octets = texte.getBytes(StandardCharsets.UTF_8);
        try (OutputStream o = getContentResolver().openOutputStream(u, "wt")) {
            if (o == null) throw new Exception("écriture impossible");
            o.write(octets);
            o.flush();
        }
        if (!lire(u).equals(texte)) throw new Exception("écriture non vérifiée : le fichier relu diffère");
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
            for (UriPermission p : getContentResolver().getPersistedUriPermissions())
                if (!p.getUri().equals(u)) {
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

    // ---------- raccourci « Nouvelle note » et menu Partager ----------

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        lireIntention(i);
        livrerNote();
    }

    private void lireIntention(Intent i) {
        if (i == null) return;
        if (NOTE.equals(i.getAction())) {
            noteEnAttente = "";
        } else if (Intent.ACTION_SEND.equals(i.getAction()) && "text/plain".equals(i.getType())) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String sujet = i.getStringExtra(Intent.EXTRA_SUBJECT);
            String texte = t == null ? "" : t.toString().trim();
            if (sujet != null && !sujet.trim().isEmpty() && !texte.startsWith(sujet.trim())) texte = sujet.trim() + "\n" + texte;
            noteEnAttente = texte;
        }
    }

    private void livrerNote() {
        if (noteEnAttente == null || !pageChargee || web == null) return;
        String js = "window.tachesAndroidNewNote&&tachesAndroidNewNote(" + JSONObject.quote(noteEnAttente) + ")";
        noteEnAttente = null;
        web.evaluateJavascript(js, null);
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
