package fr.froisois.taches;

import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Accès direct au OneDrive professionnel, comme le ferait un navigateur : la session web (cookies)
 * ouverte dans ConnexionActivity sert à lire et écrire le fichier par son chemin, via l'interface
 * REST de SharePoint. Plus d'intermédiaire (l'appli OneDrive du téléphone), plus de référence interne
 * qui change quand le PC remplace le fichier.
 */
final class OneDriveWeb {
    private OneDriveWeb() { }

    /** Navigateur Chrome ordinaire : la mention « ; wv » et « Version/x » de WebView sont retirées. */
    static String agent(Context c) {
        return WebSettings.getDefaultUserAgent(c).replace("; wv", "").replaceAll(" Version/\\d+(\\.\\d+)*", "");
    }

    static final class Exc extends Exception {
        final String code;
        Exc(String code, String msg) { super(msg); this.code = code; }
    }

    /** https://hote/personal/x/Documents/Tâches/taches.md → { site, chemin relatif au serveur } */
    private static String[] decoupe(String url) throws Exc {
        Uri u = Uri.parse(url);
        String chemin = u.getPath();
        if (chemin == null) throw new Exc("adresse", "adresse du fichier incomplète");
        String[] p = chemin.split("/");
        if (p.length < 4 || !("personal".equals(p[1]) || "sites".equals(p[1])))
            throw new Exc("adresse", "adresse OneDrive incomplète : il faut …/personal/…/Documents dans les réglages ⚙ du PC");
        String site = u.getScheme() + "://" + u.getHost() + "/" + p[1] + "/" + p[2];
        return new String[] { site, chemin };
    }

    private static String api(String site, String chemin) {
        String enc = Uri.encode(chemin.replace("'", "''"), "/");
        return site + "/_api/web/GetFileByServerRelativePath(decodedurl='" + enc + "')/$value";
    }

    private static HttpURLConnection ouvre(Context c, String url, String methode) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(false);
        h.setConnectTimeout(15000);
        h.setReadTimeout(20000);
        h.setRequestMethod(methode);
        h.setRequestProperty("User-Agent", agent(c));
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) h.setRequestProperty("Cookie", cookies);
        return h;
    }

    private static byte[] corps(HttpURLConnection h) throws Exception {
        try (InputStream in = h.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }

    private static void verifie(HttpURLConnection h) throws Exception {
        int r = h.getResponseCode();
        if (r == 401 || r == 403 || (r >= 300 && r < 400)) throw new Exc("connexion", "connexion à OneDrive nécessaire");
        if (r == 404) throw new Exc("introuvable", "fichier introuvable sur OneDrive");
        if (r == 412) throw new Exc("conflit", "le fichier a changé entre-temps");
        if (r >= 400) throw new Exc("http", "OneDrive a répondu " + r);
    }

    /** Lit le fichier : { text, etag } */
    static JSONObject lire(Context c, String url) throws Exception {
        String[] d = decoupe(url);
        HttpURLConnection h = ouvre(c, api(d[0], d[1]), "GET");
        h.setRequestProperty("Accept", "*/*");
        verifie(h);
        String t = new String(corps(h), StandardCharsets.UTF_8);
        if (t.startsWith("﻿")) t = t.substring(1);
        JSONObject o = new JSONObject();
        o.put("text", t);
        o.put("etag", h.getHeaderField("ETag") == null ? "" : h.getHeaderField("ETag"));
        return o;
    }

    /** Écrit le fichier ; si etag est fourni, refuse d'écraser une version plus récente (conflit) */
    static void ecrire(Context c, String url, String texte, String etag) throws Exception {
        String[] d = decoupe(url);
        HttpURLConnection g = ouvre(c, d[0] + "/_api/contextinfo", "POST");
        g.setRequestProperty("Accept", "application/json;odata=nometadata");
        g.setDoOutput(true);
        g.setFixedLengthStreamingMode(0);
        g.getOutputStream().close();
        verifie(g);
        String digest = new JSONObject(new String(corps(g), StandardCharsets.UTF_8)).getString("FormDigestValue");
        HttpURLConnection h = ouvre(c, api(d[0], d[1]), "POST");
        h.setRequestProperty("X-HTTP-Method", "PUT");
        h.setRequestProperty("X-RequestDigest", digest);
        h.setRequestProperty("If-Match", etag == null || etag.isEmpty() ? "*" : etag);
        byte[] b = texte.getBytes(StandardCharsets.UTF_8);
        h.setDoOutput(true);
        h.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = h.getOutputStream()) { o.write(b); }
        verifie(h);
    }
}
