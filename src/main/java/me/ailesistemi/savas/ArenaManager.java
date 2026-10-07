package me.ailesistemi.savas;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import me.ailesistemi.AileSistemi;
import me.ailesistemi.Mesaj;

/** Arenaların tanımı (arenalar.yml) ve /aile admin arena komutları. */
public class ArenaManager {

    private final AileSistemi plugin;
    private final File dosya;
    private final Map<String, Arena> arenalar = new LinkedHashMap<>();
    // Dünyası yüklü olmayan arenaların ham kaydı: dosyadan silinmesinler diye aynen geri yazılır
    private final Map<String, Map<String, Object>> yuklenemeyenler = new LinkedHashMap<>();

    public ArenaManager(AileSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "arenalar.yml");
        yukle();
    }

    private Mesaj m() { return plugin.mesaj(); }

    public Collection<Arena> arenalar() { return arenalar.values(); }

    public Arena bul(String isim) { return arenalar.get(isim.toLowerCase(Locale.ROOT)); }

    /** Kullanımda olmayan, kurulumu tamam bir arena. */
    public Arena bosArena() {
        for (Arena a : arenalar.values()) if (a.hazir() && a.kullanan == null) return a;
        return null;
    }

    /** Konumun bulunduğu arena (yoksa null). */
    public Arena arenaAt(Location loc) {
        for (Arena a : arenalar.values()) if (a.icinde(loc)) return a;
        return null;
    }

    // ------------------------------------------------------------------ KOMUTLAR
    public void komut(CommandSender s, String[] args) {
        // args: admin arena <islem> [isim]
        if (args.length < 3) { kullanim(s); return; }
        String islem = args[2].toLowerCase(Locale.ROOT);
        if (islem.equals("liste")) { liste(s); return; }
        if (args.length < 4) { kullanim(s); return; }
        String isim = args[3].toLowerCase(Locale.ROOT);

        if (islem.equals("sil")) {
            Arena a = arenalar.get(isim);
            if (a == null) { m().gonder(s, "arena-yok", "&cBöyle bir arena yok."); return; }
            if (a.kullanan != null) { m().gonder(s, "arena-kullanimda", "&cBu arenada şu an savaş var, silinemez."); return; }
            arenalar.remove(isim);
            yuklenemeyenler.remove(isim);
            kaydet();
            m().gonder(s, "arena-silindi", "&a{arena} arenası silindi.", "arena", isim);
            return;
        }
        if (!(s instanceof Player p)) { m().gonder(s, "sadece-oyuncu", "&cBu komut sadece oyun içinden kullanılabilir."); return; }

        if (islem.equals("kur")) {
            if (arenalar.containsKey(isim)) { m().gonder(p, "arena-var", "&cBu isimde bir arena zaten var."); return; }
            arenalar.put(isim, new Arena(isim));
            kaydet();
            m().gonder(p, "arena-kuruldu", "&a{arena} arenası oluşturuldu. Şimdi sınırları ve spawnları ayarlayın: &e/aile admin arena <pos1|pos2|spawn1|spawn2> {arena}", "arena", isim);
            return;
        }
        Arena a = arenalar.get(isim);
        if (a == null) { m().gonder(p, "arena-yok", "&cBöyle bir arena yok."); return; }
        if (a.kullanan != null) { m().gonder(p, "arena-kullanimda-degistir", "&cBu arenada şu an savaş var, değiştirilemez."); return; }
        Location loc = p.getLocation().clone();
        switch (islem) {
            case "pos1" -> a.pos1 = loc.getBlock().getLocation();
            case "pos2" -> a.pos2 = loc.getBlock().getLocation();
            case "spawn1" -> a.spawn1 = loc;
            case "spawn2" -> a.spawn2 = loc;
            default -> { kullanim(s); return; }
        }
        kaydet();
        m().gonder(p, "arena-ayarlandi", "&a{arena} arenasının {nokta} noktası ayarlandı.", "arena", isim, "nokta", islem);
        if (a.hazir()) {
            if ((a.spawn1 != null && !a.icinde(a.spawn1)) || (a.spawn2 != null && !a.icinde(a.spawn2))) {
                m().gonder(p, "arena-spawn-disarida", "&eUyarı: spawn noktalarından biri arena sınırlarının dışında!");
            } else {
                m().gonder(p, "arena-hazir", "&a{arena} arenası kullanıma hazır.", "arena", isim);
            }
        } else {
            m().gonder(p, "arena-eksik", "&7Eksik: &e{eksik}", "eksik", a.eksikler());
        }
    }

    private void liste(CommandSender s) {
        if (arenalar.isEmpty()) { m().gonder(s, "arena-liste-bos", "&7Hiç arena tanımlanmamış. &e/aile admin arena kur <isim>"); return; }
        s.sendMessage(m().metin("arena-liste-baslik", "&6&l--- ARENALAR ---"));
        for (Arena a : arenalar.values()) {
            String durum = !a.hazir() ? "&cEksik: " + a.eksikler() : a.kullanan != null ? "&eSavaşta" : "&aHazır";
            s.sendMessage(Mesaj.renk("&e" + a.isim + " &7- " + durum));
        }
    }

    private void kullanim(CommandSender s) {
        m().gonder(s, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/aile admin arena <kur|sil|pos1|pos2|spawn1|spawn2|liste> [isim]");
    }

    public List<String> isimler() { return new ArrayList<>(arenalar.keySet()); }

    // ------------------------------------------------------------------ KAYIT
    // Konumlar "dünya;x;y;z;yaw;pitch" metni olarak saklanır: dünyası yüklü olmayan bir arena
    // dosyanın geri kalanının yüklenmesini bozmaz ve kayıtta kaybolmaz.
    public static String konumYaz(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    /** Dünya yüklü değilse ya da metin bozuksa null. */
    public static Location konumOku(String metin) {
        if (metin == null) return null;
        String[] p = metin.split(";");
        if (p.length < 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    p.length > 4 ? Float.parseFloat(p[4]) : 0f, p.length > 5 ? Float.parseFloat(p[5]) : 0f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void kaydet() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Map<String, Object>> ham : yuklenemeyenler.entrySet()) {
            if (!arenalar.containsKey(ham.getKey())) y.createSection("arenalar." + ham.getKey(), ham.getValue());
        }
        for (Arena a : arenalar.values()) {
            String yol = "arenalar." + a.isim;
            y.set(yol + ".pos1", konumYaz(a.pos1));
            y.set(yol + ".pos2", konumYaz(a.pos2));
            y.set(yol + ".spawn1", konumYaz(a.spawn1));
            y.set(yol + ".spawn2", konumYaz(a.spawn2));
        }
        try {
            y.save(dosya);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "arenalar.yml kaydedilemedi", e);
        }
    }

    private void yukle() {
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
        ConfigurationSection bolum = y.getConfigurationSection("arenalar");
        if (bolum == null) return;
        for (String isim : bolum.getKeys(false)) {
            ConfigurationSection c = bolum.getConfigurationSection(isim);
            if (c == null) continue;
            Arena a = new Arena(isim);
            a.pos1 = konumOku(c.getString("pos1"));
            a.pos2 = konumOku(c.getString("pos2"));
            a.spawn1 = konumOku(c.getString("spawn1"));
            a.spawn2 = konumOku(c.getString("spawn2"));
            boolean eksikDunya = (c.getString("pos1") != null && a.pos1 == null) || (c.getString("pos2") != null && a.pos2 == null)
                    || (c.getString("spawn1") != null && a.spawn1 == null) || (c.getString("spawn2") != null && a.spawn2 == null);
            if (eksikDunya) {
                yuklenemeyenler.put(isim, c.getValues(false));
                plugin.getLogger().warning("'" + isim + "' arenasının dünyası yüklü değil, arena şimdilik kullanılamaz.");
                continue;
            }
            arenalar.put(isim, a);
        }
    }
}
