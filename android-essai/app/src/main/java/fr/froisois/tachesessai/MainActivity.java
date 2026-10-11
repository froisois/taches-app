package fr.froisois.tachesessai;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Appli d'essai : vérifie qu'une appli Android peut choisir taches.md dans le OneDrive pro,
 * garder l'autorisation d'un lancement à l'autre, et réécrire le fichier.
 */
public class MainActivity extends Activity {
    private static final int CHOISIR = 1;
    private SharedPreferences prefs;
    private TextView sortie;
    private final StringBuilder rapport = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("essai", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        col.setPadding(p, p, p, p);
        sv.addView(col);

        TextView intro = new TextView(this);
        intro.setText("Essai d'écriture dans OneDrive.\n\nUtilise le taches.md DE TEST, pas ton vrai fichier : "
                + "l'essai ajoute une ligne en haut du fichier.\n\n"
                + "1. Choisir le fichier (OneDrive, compte pro)\n"
                + "2. Ajouter une ligne d'essai\n"
                + "3. Fermer complètement l'appli, la rouvrir : le fichier doit se relire tout seul\n"
                + "4. Copier le rapport et le coller à Claude");
        intro.setTextSize(15);
        col.addView(intro);

        col.addView(bouton("1. Choisir le fichier", v -> choisir()));
        col.addView(bouton("2. Ajouter une ligne d'essai", v -> ecrire()));
        col.addView(bouton("Relire le fichier", v -> { Uri u = memorise(); if (u != null) lire(u); }));
        col.addView(bouton("Copier le rapport", v -> copier()));

        sortie = new TextView(this);
        sortie.setTypeface(Typeface.MONOSPACE);
        sortie.setTextSize(12);
        sortie.setTextIsSelectable(true);
        sortie.setPadding(0, p, 0, 0);
        col.addView(sortie);
        setContentView(sv);

        note("Lancement le " + maintenant());
        note("Appareil : " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
        Uri u = memorise();
        if (u == null) {
            note("Aucun fichier mémorisé (normal au premier lancement).");
        } else {
            note("Fichier mémorisé d'un lancement précédent.");
            permissions();
            infos(u);
            lire(u);
        }
    }

    private Button bouton(String texte, android.view.View.OnClickListener action) {
        Button bt = new Button(this);
        bt.setText(texte);
        bt.setAllCaps(false);
        bt.setOnClickListener(action);
        bt.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bt;
    }

    private void choisir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, CHOISIR);
        } catch (Exception e) {
            note("ÉCHEC ouverture du sélecteur : " + e);
        }
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code != CHOISIR) return;
        if (res != RESULT_OK || data == null || data.getData() == null) { note("Choix annulé."); return; }
        Uri u = data.getData();
        note("Fichier choisi : " + u.getAuthority());
        try {
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            note("OK  autorisation lecture + écriture conservée");
        } catch (Exception e) {
            note("ÉCHEC autorisation écriture conservée : " + e.getMessage());
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                note("OK  autorisation lecture seule conservée");
            } catch (Exception e2) {
                note("ÉCHEC aucune autorisation conservée : " + e2.getMessage());
            }
        }
        prefs.edit().putString("uri", u.toString()).apply();
        infos(u);
        lire(u);
    }

    private Uri memorise() {
        String s = prefs.getString("uri", null);
        if (s == null) { note("Choisis d'abord un fichier."); return null; }
        return Uri.parse(s);
    }

    private void permissions() {
        StringBuilder sb = new StringBuilder();
        for (UriPermission up : getContentResolver().getPersistedUriPermissions()) {
            sb.append(up.isReadPermission() ? "lecture " : "").append(up.isWritePermission() ? "écriture " : "");
        }
        note("Autorisations conservées : " + (sb.length() == 0 ? "aucune" : sb.toString().trim()));
    }

    private void infos(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c == null || !c.moveToFirst()) { note("Pas d'informations sur le fichier."); return; }
            int iNom = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int iTaille = c.getColumnIndex(OpenableColumns.SIZE);
            int iModif = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            int iDrap = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);
            note("Nom : " + (iNom >= 0 ? c.getString(iNom) : "?") + " · " + (iTaille >= 0 ? c.getLong(iTaille) + " octets" : "taille ?"));
            if (iModif >= 0 && !c.isNull(iModif)) note("Modifié le : " + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(new Date(c.getLong(iModif))));
            if (iDrap >= 0) {
                int f = c.getInt(iDrap);
                note("Le fournisseur annonce l'écriture : " + ((f & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0 ? "oui" : "non"));
            }
        } catch (Exception e) {
            note("ÉCHEC lecture des informations : " + e.getMessage());
        }
    }

    private String texte(Uri u) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("flux vide");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void lire(Uri u) {
        try {
            String t = texte(u);
            String[] lignes = t.split("\n", -1);
            int cases = 0;
            for (String l : lignes) if (l.trim().startsWith("- [")) cases++;
            note("OK  lecture : " + lignes.length + " lignes, " + cases + " cases à cocher");
            StringBuilder debut = new StringBuilder();
            for (int k = 0; k < Math.min(3, lignes.length); k++) debut.append("    ").append(lignes[k]).append("\n");
            note("Début du fichier :\n" + debut);
        } catch (Exception e) {
            note("ÉCHEC lecture : " + e);
        }
    }

    private void ecrire() {
        Uri u = memorise();
        if (u == null) return;
        String ligne = "- [ ] Essai depuis l'appli Android " + maintenant() + " {créé "
                + new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date()) + "}";
        try {
            String avant = texte(u);
            byte[] octets = (ligne + "\n" + avant).getBytes(StandardCharsets.UTF_8);
            OutputStream out;
            try {
                out = getContentResolver().openOutputStream(u, "wt");
            } catch (Exception e) {
                note("Mode « wt » refusé (" + e.getMessage() + "), essai en mode « w »");
                out = getContentResolver().openOutputStream(u, "w");
            }
            if (out == null) throw new Exception("flux d'écriture vide");
            try (OutputStream o = out) { o.write(octets); o.flush(); }
            String apres = texte(u);
            if (apres.startsWith(ligne) && apres.length() == avant.length() + ligne.length() + 1)
                note("OK  écriture vérifiée : la ligne est en tête du fichier, rien d'autre n'a bougé");
            else
                note("ÉCHEC écriture : le fichier relu ne correspond pas (" + apres.length() + " caractères au lieu de " + (avant.length() + ligne.length() + 1) + ")");
            infos(u);
            Toast.makeText(this, "Écrit. Vérifie dans OneDrive (PC ou appli) que la ligne y est.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            note("ÉCHEC écriture : " + e);
        }
    }

    private void copier() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Rapport", "Rapport appli d'essai\n" + rapport));
        Toast.makeText(this, "Rapport copié", Toast.LENGTH_SHORT).show();
    }

    private String maintenant() {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(new Date());
    }

    private void note(String s) {
        rapport.append(s).append("\n");
        if (sortie != null) sortie.setText(rapport);
    }
}
