package me.klansistemi.vahsikale;

import java.io.File;
import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Prosedürel yapıların (Vahşi Kale / Zindan) kurulamayacağı alanlar: köy bölgesi (MeslekSistemi koy.yml)
 * ve tüm GriefPrevention claim'leri. Sadece yapı merkezi değil, merkez etrafındaki (yarıçap + tampon)
 * karenin tamamı taranır; 8 blokluk adımla örneklendiği için en küçük claim bile (GP asgari 10x10) yakalanır.
 * GriefPrevention yoksa / API uyumsuzsa claim kontrolü sessizce atlanır (köy kontrolü bağımsız çalışır).
 */
public final class YasakAlan {

    private static final int ADIM = 8;
    private static volatile double[] koyDikdortgeni; // {minX, maxX, minZ, maxZ}
    private static volatile String koyDunyasi;
    private static volatile long koyOkumaZamani = 0L;
    private static Method getClaimAt;
    private static Object dataStore;

    private YasakAlan() {}

    /** true: bu merkez + yarıçap yasak alana (köy ya da claim) değiyor, yapı kurulmamalı. */
    public static boolean engelliMi(World dunya, int x, int z, int yaricap) {
        if (koyaDegiyor(dunya, x, z, yaricap)) return true;
        return claimeDegiyor(dunya, x, z, yaricap);
    }

    /** {minX,maxX,minZ,maxZ} + dunya adi; yoksa null (Meslek koy.yml). */
    public static double[] koyDikdortgeniGetir() { koyuOku(); return koyDikdortgeni; }
    public static String koyDunyasiGetir() { koyuOku(); return koyDunyasi; }

    private static void koyuOku() {
        long simdi = System.currentTimeMillis();
        if (simdi - koyOkumaZamani < 30_000L) return;
        koyOkumaZamani = simdi;
        koyDikdortgeni = null; koyDunyasi = null;
        Plugin meslek = Bukkit.getPluginManager().getPlugin("MeslekSistemi");
        if (meslek == null) return;
        File f = new File(meslek.getDataFolder(), "koy.yml");
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        if (!y.contains("pos1.x") || !y.contains("pos2.x")) return;
        String d1 = y.getString("pos1.dunya"), d2 = y.getString("pos2.dunya");
        if (d1 == null || !d1.equals(d2)) return;
        koyDunyasi = d1;
        double x1 = y.getDouble("pos1.x"), x2 = y.getDouble("pos2.x"), z1 = y.getDouble("pos1.z"), z2 = y.getDouble("pos2.z");
        koyDikdortgeni = new double[]{Math.min(x1, x2), Math.max(x1, x2), Math.min(z1, z2), Math.max(z1, z2)};
    }

    public static boolean koyaDegiyor(World dunya, int x, int z, int yaricap) {
        koyuOku();
        double[] k = koyDikdortgeni;
        if (k == null || koyDunyasi == null || !koyDunyasi.equals(dunya.getName())) return false;
        return x + yaricap >= k[0] && x - yaricap <= k[1] && z + yaricap >= k[2] && z - yaricap <= k[3];
    }

    public static boolean claimeDegiyor(World dunya, int x, int z, int yaricap) {
        if (!Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) return false;
        try {
            if (getClaimAt == null) {
                Class<?> gp = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
                Object instance = gp.getField("instance").get(null);
                dataStore = gp.getMethod("getDataStore").invoke(instance);
                getClaimAt = dataStore.getClass().getMethod("getClaimAt", Location.class, boolean.class, Class.forName("me.ryanhamshire.GriefPrevention.Claim"));
            }
            for (int dx = -yaricap; dx <= yaricap + ADIM; dx += ADIM) {
                for (int dz = -yaricap; dz <= yaricap + ADIM; dz += ADIM) {
                    int px = x + Math.min(dx, yaricap), pz = z + Math.min(dz, yaricap);
                    // Yükseklik yok sayılır (3D alt claim'ler ve yer altı yapıları için)
                    if (getClaimAt.invoke(dataStore, new Location(dunya, px, dunya.getMinHeight() + 1, pz), true, null) != null) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false; // GP API uyumsuz: engelleme yapma (mevcut mesafe kontrolleri geçerli)
        }
    }
}
