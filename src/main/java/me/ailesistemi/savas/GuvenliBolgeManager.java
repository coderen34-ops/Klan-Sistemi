package me.ailesistemi.savas;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import me.ailesistemi.AileSistemi;
import me.ailesistemi.Mesaj;

/**
 * Prestij puanı verilmeyen güvenli bölgeler:
 *  - GriefPrevention claim'lerinin içi (evler, arsalar, admin claim'leri)
 *  - Köy merkezi çevresi (varsayılan 400 blok çap) — /aile admin guvenli merkez
 *  - Elle tanımlanan bölgeler — /aile admin guvenli <kur|sil|pos1|pos2|liste> [isim]
 *  - Arenalar
 * Kayıt: guvenli-bolgeler.yml
 */
public class GuvenliBolgeManager {

    private static class Bolge {
        final String isim;
        Location pos1, pos2;
        Bolge(String isim) { this.isim = isim; }
        boolean hazir() { return pos1 != null && pos2 != null && pos1.getWorld() != null && pos1.getWorld().equals(pos2.getWorld()); }
        boolean icinde(Location l) {
            if (!hazir() || l == null || l.getWorld() == null || !l.getWorld().equals(pos1.getWorld())) return false;
            return arada(l.getX(), pos1.getX(), pos2.getX()) && arada(l.getY(), pos1.getY(), pos2.getY()) && arada(l.getZ(), pos1.getZ(), pos2.getZ());
        }
        private static boolean arada(double d, double a, double b) { return d >= Math.min(a, b) && d <= Math.max(a, b) + 1; }
    }

    private final AileSistemi plugin;
    private final File dosya;
    private final Map<String, Bolge> bolgeler = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> yuklenemeyenler = new LinkedHashMap<>();
    private Location merkez;
    private String merkezHam; // Dünyası yüklü değilse ham kaydı korunur

    public GuvenliBolgeManager(AileSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "guvenli-bolgeler.yml");
        yukle();
    }

    private Mesaj m() { return plugin.mesaj(); }

    private double merkezYaricap() { return plugin.getConfig().getDouble("prestij.merkez-cap", 400) / 2.0; }
    private boolean claimKorumasi() { return plugin.getConfig().getBoolean("prestij.claimler-guvenli", true); }

    /** Konum bir güvenli bölgede mi? (claim, köy merkezi, tanımlı bölge ya da arena) */
    public boolean guvenliMi(Location loc) {
        if (loc == null || loc.getWorld() == null) return true;
        if (merkez != null && loc.getWorld().equals(merkez.getWorld())) {
            double dx = loc.getX() - merkez.getX(), dz = loc.getZ() - merkez.getZ();
            double r = merkezYaricap();
            if (dx * dx + dz * dz <= r * r) return true;
        }
        if (claimKorumasi() && claimIcinde(loc)) return true;
        for (Bolge b : bolgeler.values()) if (b.icinde(loc)) return true;
        return plugin.arenalar().arenaAt(loc) != null;
    }

    /** GriefPrevention kurulu değilse ya da hata verirse claim kontrolü atlanır. */
    private boolean claimIcinde(Location loc) {
        try {
            if (!org.bukkit.Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) return false;
            return me.ryanhamshire.GriefPrevention.GriefPrevention.instance.dataStore.getClaimAt(loc, true, null) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public List<String> isimler() { return new ArrayList<>(bolgeler.keySet()); }

    public void komut(CommandSender s, String[] args) {
        // args: admin guvenli <islem> [isim]
        if (args.length < 3) { kullanim(s); return; }
        String islem = args[2].toLowerCase(Locale.ROOT);
        if (islem.equals("merkez")) {
            if (!(s instanceof Player p)) { m().gonder(s, "sadece-oyuncu", "&cBu komut sadece oyun içinden kullanılabilir."); return; }
            merkez = p.getLocation().getBlock().getLocation();
            merkezHam = null;
            kaydet();
            m().gonder(p, "guvenli-merkez", "&aKöy merkezi ayarlandı. Çevresindeki {cap} blok çaplı alan güvenli bölge.", "cap", (int) (merkezYaricap() * 2));
            return;
        }
        if (islem.equals("liste")) {
            s.sendMessage(m().metin("guvenli-merkez-bilgi", "&7Köy merkezi: &f{merkez} &7| Çap: &f{cap} &7| Claim'ler güvenli: &f{claim}",
                    "merkez", merkez == null ? "ayarlanmadı (/aile admin guvenli merkez)" : merkez.getWorld().getName() + " " + merkez.getBlockX() + ", " + merkez.getBlockZ(),
                    "cap", (int) (merkezYaricap() * 2), "claim", claimKorumasi() ? "evet" : "hayır"));
            if (bolgeler.isEmpty()) { m().gonder(s, "guvenli-liste-bos", "&7Hiç güvenli bölge tanımlanmamış. &e/aile admin guvenli kur <isim>"); return; }
            s.sendMessage(m().metin("guvenli-liste-baslik", "&6&l--- GÜVENLİ BÖLGELER ---"));
            for (Bolge b : bolgeler.values()) s.sendMessage(Mesaj.renk("&e" + b.isim + " &7- " + (b.hazir() ? "&aHazır" : "&cEksik (pos1/pos2)")));
            return;
        }
        if (args.length < 4) { kullanim(s); return; }
        String isim = args[3].toLowerCase(Locale.ROOT);
        switch (islem) {
            case "kur" -> {
                if (bolgeler.containsKey(isim)) { m().gonder(s, "guvenli-var", "&cBu isimde bir güvenli bölge zaten var."); return; }
                bolgeler.put(isim, new Bolge(isim));
                kaydet();
                m().gonder(s, "guvenli-kuruldu", "&a{bolge} güvenli bölgesi oluşturuldu. Köşeleri ayarlayın: &e/aile admin guvenli <pos1|pos2> {bolge}", "bolge", isim);
            }
            case "sil" -> {
                boolean silindi = bolgeler.remove(isim) != null;
                silindi |= yuklenemeyenler.remove(isim) != null;
                if (!silindi) { m().gonder(s, "guvenli-yok", "&cBöyle bir güvenli bölge yok."); return; }
                kaydet();
                m().gonder(s, "guvenli-silindi", "&a{bolge} güvenli bölgesi silindi.", "bolge", isim);
            }
            case "pos1", "pos2" -> {
                if (!(s instanceof Player p)) { m().gonder(s, "sadece-oyuncu", "&cBu komut sadece oyun içinden kullanılabilir."); return; }
                Bolge b = bolgeler.get(isim);
                if (b == null) { m().gonder(s, "guvenli-yok", "&cBöyle bir güvenli bölge yok."); return; }
                if (islem.equals("pos1")) b.pos1 = p.getLocation().getBlock().getLocation();
                else b.pos2 = p.getLocation().getBlock().getLocation();
                kaydet();
                m().gonder(p, "guvenli-ayarlandi", "&a{bolge} bölgesinin {nokta} noktası ayarlandı.", "bolge", isim, "nokta", islem);
            }
            default -> kullanim(s);
        }
    }

    private void kullanim(CommandSender s) {
        m().gonder(s, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/aile admin guvenli <merkez|kur|sil|pos1|pos2|liste> [isim]");
    }

    private void kaydet() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("merkez", merkez != null ? ArenaManager.konumYaz(merkez) : merkezHam);
        for (Map.Entry<String, Map<String, Object>> ham : yuklenemeyenler.entrySet()) {
            if (!bolgeler.containsKey(ham.getKey())) y.createSection("bolgeler." + ham.getKey(), ham.getValue());
        }
        for (Bolge b : bolgeler.values()) {
            y.set("bolgeler." + b.isim + ".pos1", ArenaManager.konumYaz(b.pos1));
            y.set("bolgeler." + b.isim + ".pos2", ArenaManager.konumYaz(b.pos2));
        }
        try {
            y.save(dosya);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "guvenli-bolgeler.yml kaydedilemedi", e);
        }
    }

    private void yukle() {
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
        String m = y.getString("merkez");
        merkez = ArenaManager.konumOku(m);
        if (m != null && merkez == null) {
            merkezHam = m;
            plugin.getLogger().warning("Köy merkezinin dünyası yüklü değil, merkez koruması şimdilik devre dışı.");
        }
        ConfigurationSection bolum = y.getConfigurationSection("bolgeler");
        if (bolum == null) return;
        for (String isim : bolum.getKeys(false)) {
            ConfigurationSection c = bolum.getConfigurationSection(isim);
            if (c == null) continue;
            Bolge b = new Bolge(isim);
            b.pos1 = ArenaManager.konumOku(c.getString("pos1"));
            b.pos2 = ArenaManager.konumOku(c.getString("pos2"));
            if ((c.getString("pos1") != null && b.pos1 == null) || (c.getString("pos2") != null && b.pos2 == null)) {
                yuklenemeyenler.put(isim, c.getValues(false));
                plugin.getLogger().warning("'" + isim + "' güvenli bölgesinin dünyası yüklü değil, bölge şimdilik devre dışı.");
                continue;
            }
            bolgeler.put(isim, b);
        }
    }
}
