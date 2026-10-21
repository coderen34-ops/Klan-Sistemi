package me.klansistemi.vahsikale;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vindicator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.Para;
import me.klansistemi.model.Klan;
import me.klansistemi.savas.ArenaManager;

/**
 * Vahşi Kaleler / NPC Klanları (Faz 4, Entry #8).
 *
 * KAPSAM (bu ilk sürüm): kale kaydı (SQLite), prosedürel isimlendirme, nöbetçi/lider NPC'leri
 * (Vindicator + LibsDisguises insan görünümü), tespit/uyarı + saldırı yarıçapı, sandık kilidi
 * (idempotent - tüm nöbetçiler+lider öldürülene kadar kırılamaz), loot'un doğrudan klan kasasına
 * aktarımı, fetih prestiji, kuşatma sırasında /home-/back-/tpa engeli, kuşatmada ölümde kale
 * girişine respawn + klan prestij kaybı.
 *
 * SONRAYA BIRAKILANLAR (changelog'da not edilecek): elçi NPC (ticaret/görev/barış menüsü),
 * fethedilen kalelerin yerine otomatik/periyodik yeni kale üretimi (şimdilik sadece admin
 * /vahsikale kur ile manuel). Fiziksel kale YAPISI (dünya üretimi) bu sınıfın işi değil -
 * admin, yapı zaten inşa edilmiş bir konumda /vahsikale kur çalıştırır.
 */
public class VahsiKaleYonetici implements Listener {

    private final KlanSistemi plugin;
    private final File dbFile;
    private Connection baglanti;

    private final NamespacedKey kaleIdKey;
    private final NamespacedKey rolKey; // "NOBETCI" ya da "LIDER"

    private final Map<UUID, VahsiKale> kaleler = new LinkedHashMap<>();
    // Oyuncu -> son uyarı anı (actionbar throttle), kale bazlı tutmaya gerek yok, genel throttle yeterli
    private final Map<UUID, Long> sonUyari = new LinkedHashMap<>();

    public VahsiKaleYonetici(KlanSistemi plugin) {
        this.plugin = plugin;
        this.dbFile = new File(plugin.getDataFolder(), "vahsikaleler.db");
        this.kaleIdKey = new NamespacedKey(plugin, "vahsikale-id");
        this.rolKey = new NamespacedKey(plugin, "vahsikale-rol");
        baglan();
        tablolariOlustur();
        yukle();
    }

    private Mesaj m() { return plugin.mesaj(); }

    // ------------------------------------------------------------------ AYARLAR (config.yml)
    private double varsayilanTespit() { return plugin.getConfig().getDouble("vahsikale.tespit-yaricap", 40); }
    private double varsayilanSaldiri() { return plugin.getConfig().getDouble("vahsikale.saldiri-yaricap", 20); }
    private int nobetciSayisi(int seviye) { return plugin.getConfig().getInt("vahsikale.seviye-" + seviye + ".nobetci-sayisi", Math.max(2, seviye * 2)); }
    private double nobetciCan(int seviye) { return plugin.getConfig().getDouble("vahsikale.seviye-" + seviye + ".nobetci-can", 20 + seviye * 10); }
    private double liderCan(int seviye) { return plugin.getConfig().getDouble("vahsikale.seviye-" + seviye + ".lider-can", 60 + seviye * 20); }
    private double nobetciHasar(int seviye) { return plugin.getConfig().getDouble("vahsikale.seviye-" + seviye + ".nobetci-hasar", 4 + seviye); }
    private double sandikLootMin(int seviye) { return plugin.getConfig().getDouble("vahsikale.seviye-" + seviye + ".sandik-min", 3000 * seviye); }
    private double sandikLootMax(int seviye) { return plugin.getConfig().getDouble("vahsikale.seviye-" + seviye + ".sandik-max", 8000 * seviye); }
    private int fetihPrestiji() { return plugin.getConfig().getInt("vahsikale.fetih-prestiji", 10); }
    private int olumPrestijKaybi() { return plugin.getConfig().getInt("vahsikale.olum-prestij-kaybi", 30); }

    // ------------------------------------------------------------------ KOMUT
    public void komut(CommandSender s, String[] args) {
        if (args.length < 1) { kullanim(s); return; }
        String alt = args[0].toLowerCase(Locale.ROOT);
        switch (alt) {
            case "kur" -> kur(s, args);
            case "sil" -> sil(s, args);
            case "liste" -> liste(s);
            case "bilgi" -> bilgi(s);
            case "sandik" -> sandikEkle(s);
            case "giris" -> girisAyarla(s);
            default -> kullanim(s);
        }
    }

    private void kullanim(CommandSender s) {
        s.sendMessage(Mesaj.renk("&cKullanım: &e/vahsikale <kur <seviye 1-5>|sil <isim>|liste|bilgi|sandik|giris>"));
    }

    private void kur(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage(Mesaj.renk("&cBu komut sadece oyun içinden kullanılabilir.")); return; }
        if (args.length < 2) { kullanim(s); return; }
        int seviye;
        try {
            seviye = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) { kullanim(s); return; }
        if (seviye < 1 || seviye > 5) { p.sendMessage(Mesaj.renk("&cSeviye 1-5 arasında olmalı.")); return; }

        VahsiKale kale = new VahsiKale(UUID.randomUUID());
        kale.seviye = seviye;
        kale.isim = IsimUretici.uret(seviye);
        kale.merkez = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        kale.giris = kale.merkez.clone();
        kale.tespitYaricap = varsayilanTespit();
        kale.saldiriYaricap = varsayilanSaldiri();
        kaleler.put(kale.id, kale);
        kaydet(kale);
        nobetcileriDogur(kale);
        p.sendMessage(Mesaj.renk("&a" + kale.isim + " kuruldu (seviye " + seviye + "). " + kale.nobetciRolleri.size()
                + " nöbetçi/lider doğdu. Sandıkları etiketlemek için üzerlerine bakıp &e/vahsikale sandik&a kullan."));
    }

    private void sil(CommandSender s, String[] args) {
        if (args.length < 2) { kullanim(s); return; }
        String aranan = String.join(" ", java.util.Arrays.asList(args).subList(1, args.length));
        VahsiKale kale = bul(aranan);
        if (kale == null) { s.sendMessage(Mesaj.renk("&cBöyle bir kale bulunamadı.")); return; }
        for (UUID npcId : kale.nobetciRolleri.keySet()) {
            org.bukkit.entity.Entity e = Bukkit.getEntity(npcId);
            if (e != null) e.remove();
        }
        kaleler.remove(kale.id);
        silDb(kale.id);
        s.sendMessage(Mesaj.renk("&a" + kale.isim + " silindi."));
    }

    private void liste(CommandSender s) {
        if (kaleler.isEmpty()) { s.sendMessage(Mesaj.renk("&7Hiç Vahşi Kale yok. &e/vahsikale kur <seviye>")); return; }
        s.sendMessage(Mesaj.renk("&6&l--- VAHŞİ KALELER ---"));
        for (VahsiKale k : kaleler.values()) {
            String durum = k.fethedildi ? "&cFethedildi" : "&a" + k.oluNobetciler.size() + "/" + k.nobetciRolleri.size() + " nöbetçi öldü";
            s.sendMessage(Mesaj.renk("&e" + k.isim + " &7(seviye " + k.seviye + ") - " + durum));
        }
    }

    private void bilgi(CommandSender s) {
        if (!(s instanceof Player p)) return;
        VahsiKale en = enYakin(p.getLocation());
        if (en == null) { p.sendMessage(Mesaj.renk("&7Yakında kayıtlı bir Vahşi Kale yok.")); return; }
        p.sendMessage(Mesaj.renk("&6" + en.isim + " &7| Seviye: &f" + en.seviye + " &7| Durum: "
                + (en.fethedildi ? "&cFethedildi" : "&a" + en.oluNobetciler.size() + "/" + en.nobetciRolleri.size() + " öldü")
                + " &7| Sandık: &f" + en.sandiklar.values().stream().filter(b -> !b).count() + " kilitli / " + en.sandiklar.size() + " toplam"));
    }

    private void sandikEkle(CommandSender s) {
        if (!(s instanceof Player p)) { s.sendMessage(Mesaj.renk("&cBu komut sadece oyun içinden kullanılabilir.")); return; }
        VahsiKale en = enYakin(p.getLocation());
        if (en == null) { p.sendMessage(Mesaj.renk("&cYakında kayıtlı bir Vahşi Kale yok, önce /vahsikale kur.")); return; }
        RayTraceResult r = p.rayTraceBlocks(6);
        if (r == null || r.getHitBlock() == null) { p.sendMessage(Mesaj.renk("&cBaktığın yerde bir blok yok.")); return; }
        Block hedef = r.getHitBlock();
        String konum = ArenaManager.konumYaz(hedef.getLocation());
        en.sandiklar.put(konum, false);
        sandikKaydet(en.id, konum, false);
        p.sendMessage(Mesaj.renk("&a" + en.isim + " kalesine sandık eklendi. Toplam: " + en.sandiklar.size()));
    }

    private void girisAyarla(CommandSender s) {
        if (!(s instanceof Player p)) { s.sendMessage(Mesaj.renk("&cBu komut sadece oyun içinden kullanılabilir.")); return; }
        VahsiKale en = enYakin(p.getLocation());
        if (en == null) { p.sendMessage(Mesaj.renk("&cYakında kayıtlı bir Vahşi Kale yok.")); return; }
        en.giris = p.getLocation().clone();
        kaydet(en);
        p.sendMessage(Mesaj.renk("&a" + en.isim + " kalesinin giriş/respawn noktası ayarlandı."));
    }

    private VahsiKale bul(String isimVeyaKismi) {
        for (VahsiKale k : kaleler.values()) if (k.isim.equalsIgnoreCase(isimVeyaKismi)) return k;
        for (VahsiKale k : kaleler.values()) if (k.isim.toLowerCase(Locale.ROOT).contains(isimVeyaKismi.toLowerCase(Locale.ROOT))) return k;
        return null;
    }

    private VahsiKale enYakin(Location l) {
        VahsiKale en = null;
        double enYakinMesafe = Double.MAX_VALUE;
        for (VahsiKale k : kaleler.values()) {
            if (k.merkez == null || k.merkez.getWorld() == null || !k.merkez.getWorld().equals(l.getWorld())) continue;
            double d = k.merkez.distanceSquared(l);
            if (d < enYakinMesafe) { enYakinMesafe = d; en = k; }
        }
        return en;
    }

    // ------------------------------------------------------------------ NÖBETÇİ/LİDER DOĞURMA
    private void nobetcileriDogur(VahsiKale kale) {
        int sayi = nobetciSayisi(kale.seviye);
        java.util.Random rng = new java.util.Random();
        for (int i = 0; i < sayi; i++) {
            double aci = rng.nextDouble() * Math.PI * 2;
            double yaricap = 2 + rng.nextDouble() * 4;
            Location konum = kale.merkez.clone().add(Math.cos(aci) * yaricap, 0, Math.sin(aci) * yaricap);
            konum.setY(enYakinYerZemin(konum));
            dogurBir(kale, konum, "NOBETCI", nobetciCan(kale.seviye), nobetciHasar(kale.seviye),
                    kale.isim + " Nöbetçisi", "MHF_Zombie");
        }
        // Lider: merkezde, daha güçlü
        Location liderKonum = kale.merkez.clone();
        liderKonum.setY(enYakinYerZemin(liderKonum));
        dogurBir(kale, liderKonum, "LIDER", liderCan(kale.seviye), nobetciHasar(kale.seviye) * 1.5,
                kale.isim + " Lideri", "MHF_Herobrine");
    }

    private double enYakinYerZemin(Location konum) {
        // Basit güvenlik: çok derinde/havada doğmasın diye highest block + 1 kullan
        if (konum.getWorld() == null) return konum.getY();
        return konum.getWorld().getHighestBlockYAt(konum) + 1.0;
    }

    private void dogurBir(VahsiKale kale, Location konum, String rol, double can, double hasar, String goruntuAdi, String skinAdi) {
        Vindicator v = konum.getWorld().spawn(konum, Vindicator.class);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.getPersistentDataContainer().set(kaleIdKey, PersistentDataType.STRING, kale.id.toString());
        v.getPersistentDataContainer().set(rolKey, PersistentDataType.STRING, rol);
        v.setCustomName(ChatColor.RED + goruntuAdi);
        v.setCustomNameVisible(true);
        AttributeInstance canOzniteligi = v.getAttribute(Attribute.MAX_HEALTH);
        if (canOzniteligi != null) canOzniteligi.setBaseValue(can);
        v.setHealth(can);
        AttributeInstance hasarOzniteligi = v.getAttribute(Attribute.ATTACK_DAMAGE);
        if (hasarOzniteligi != null) hasarOzniteligi.setBaseValue(hasar);
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) {
                NobetciDisguiseYardimcisi.giydir(v, skinAdi, goruntuAdi);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Vahşi Kale NPC'si giydirilemedi (LibsDisguises sorunu)", t);
        }
        kale.nobetciRolleri.put(v.getUniqueId(), rol);
        nobetciKaydet(kale.id, v.getUniqueId(), rol, false);
    }

    /** Sunucu restart'ında LibsDisguises tüm disguise'ları kaybeder; mevcut nöbetçiler bulunup yeniden giydirilir. */
    private void disguiseleriTazele() {
        if (!Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) return;
        for (VahsiKale kale : kaleler.values()) {
            for (Map.Entry<UUID, String> e : kale.nobetciRolleri.entrySet()) {
                if (kale.oluNobetciler.contains(e.getKey())) continue;
                org.bukkit.entity.Entity ent = Bukkit.getEntity(e.getKey());
                if (ent instanceof LivingEntity le) {
                    String skin = "LIDER".equals(e.getValue()) ? "MHF_Herobrine" : "MHF_Zombie";
                    String goruntuAdi = kale.isim + ("LIDER".equals(e.getValue()) ? " Lideri" : " Nöbetçisi");
                    try {
                        NobetciDisguiseYardimcisi.giydir(le, skin, goruntuAdi);
                    } catch (Throwable t) {
                        plugin.getLogger().log(Level.WARNING, "Vahşi Kale disguise tazeleme hatası", t);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ TESPİT / SALDIRI (periyodik)
    public void tick() {
        long simdi = System.currentTimeMillis();
        for (VahsiKale kale : kaleler.values()) {
            if (kale.fethedildi || kale.merkez == null || kale.merkez.getWorld() == null) continue;
            for (Player p : kale.merkez.getWorld().getPlayers()) {
                double mesafe = p.getLocation().distance(kale.merkez);
                boolean icerde = mesafe <= kale.saldiriYaricap;
                if (icerde) {
                    if (kale.kusatmadakiOyuncular.add(p.getUniqueId())) {
                        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
                        if (klan != null) plugin.klanManager().klanaGonder(klan, "vahsikale-kusatma",
                                "&c{oyuncu} &7bir Vahşi Kaleye (&e{kale}&7) saldırıyor!", "oyuncu", p.getName(), "kale", kale.isim);
                    }
                    saldirTetikle(kale, p);
                } else {
                    kale.kusatmadakiOyuncular.remove(p.getUniqueId());
                    if (mesafe <= kale.tespitYaricap) {
                        Long son = sonUyari.get(p.getUniqueId());
                        if (son == null || simdi - son > 5000L) {
                            p.sendActionBar(Mesaj.renk("&c" + kale.isim + " Klanı'nın Kalesi - Buradan uzak dur!"));
                            sonUyari.put(p.getUniqueId(), simdi);
                        }
                    }
                }
            }
        }
    }

    private void saldirTetikle(VahsiKale kale, Player p) {
        for (UUID npcId : kale.nobetciRolleri.keySet()) {
            if (kale.oluNobetciler.contains(npcId)) continue;
            org.bukkit.entity.Entity e = Bukkit.getEntity(npcId);
            if (e instanceof Mob mob && mob.isValid() && (mob.getTarget() == null || !mob.getTarget().equals(p))) {
                if (mob.getLocation().distance(p.getLocation()) <= kale.saldiriYaricap + 5) mob.setTarget(p);
            }
        }
    }

    // ------------------------------------------------------------------ OLAYLAR
    @EventHandler(priority = EventPriority.MONITOR)
    public void onOlum(EntityDeathEvent event) {
        String kaleIdStr = event.getEntity().getPersistentDataContainer().get(kaleIdKey, PersistentDataType.STRING);
        if (kaleIdStr == null) return;
        VahsiKale kale = kaleler.get(UUID.fromString(kaleIdStr));
        if (kale == null) return;
        kale.oluNobetciler.add(event.getEntity().getUniqueId());
        nobetciOldu(kale.id, event.getEntity().getUniqueId());
        if (kale.tumuOldu()) {
            Bukkit.getOnlinePlayers().forEach(pl -> {
                if (pl.getLocation().getWorld().equals(kale.merkez.getWorld()) && pl.getLocation().distance(kale.merkez) <= kale.tespitYaricap * 2) {
                    pl.sendMessage(Mesaj.renk("&6" + kale.isim + " &7savunması çökertildi! Sandıklar artık kırılabilir."));
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSandikKir(BlockBreakEvent event) {
        String konum = ArenaManager.konumYaz(event.getBlock().getLocation());
        VahsiKale kale = null;
        for (VahsiKale k : kaleler.values()) if (k.sandiklar.containsKey(konum)) { kale = k; break; }
        if (kale == null) return;
        if (Boolean.TRUE.equals(kale.sandiklar.get(konum))) return; // zaten açıldı, normal blok kırma davranışı
        if (!kale.tumuOldu()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Mesaj.renk("&cÖnce bu kalenin tüm nöbetçilerini ve liderini yok etmelisin."));
            return;
        }
        // İdempotent kilit açma: iki oyuncu aynı anda kırarsa bile sadece bir kez ödül verilir
        kale.sandiklar.put(konum, true);
        sandikKaydet(kale.id, konum, true);
        event.setDropItems(false);

        Klan klan = plugin.klanManager().oyuncununKlani(event.getPlayer().getUniqueId());
        if (klan == null) {
            event.getPlayer().sendMessage(Mesaj.renk("&eSandığı kırdın ama bir klana üye olmadığın için ödül bir yere aktarılamadı."));
            return;
        }
        double miktar = Para.kurus(sandikLootMin(kale.seviye) + Math.random() * (sandikLootMax(kale.seviye) - sandikLootMin(kale.seviye)));
        klan.kasa = Para.kurus(klan.kasa + miktar);
        plugin.log().yaz(klan, event.getPlayer().getName(), "VAHSI_KALE_LOOT", kale.isim + " -> " + Para.yaz(miktar));
        event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        plugin.klanManager().klanaGonder(klan, "vahsikale-loot", "&a{oyuncu} &7{kale} sandığını kırdı: &e{miktar}&7 klan kasasına aktarıldı.",
                "oyuncu", event.getPlayer().getName(), "kale", kale.isim, "miktar", Para.yaz(miktar));

        if (!kale.prestijVerildi) {
            boolean hepsiAcildi = kale.sandiklar.values().stream().allMatch(Boolean::booleanValue);
            if (hepsiAcildi || kale.sandiklar.isEmpty()) {
                kale.prestijVerildi = true;
                kale.fethedildi = true;
                kale.fetihedenKlanId = klan.id;
                kale.fetihZamani = System.currentTimeMillis();
                klan.prestij += fetihPrestiji();
                plugin.veri().kaydet();
                kaydet(kale);
                plugin.klanManager().klanaGonder(klan, "vahsikale-fetih",
                        "&6{kale} &7tamamen fethedildi! Klanınız &e+{prestij} prestij&7 kazandı.",
                        "kale", kale.isim, "prestij", fetihPrestiji());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOyuncuOlum(PlayerDeathEvent event) {
        olumNotEt(event.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        VahsiKale kale = kaleOlumYeri.remove(p.getUniqueId());
        if (kale != null && kale.giris != null && kale.giris.getWorld() != null) {
            event.setRespawnLocation(kale.giris);
        }
    }

    private final Map<UUID, VahsiKale> kaleOlumYeri = new LinkedHashMap<>();

    /** SaglikManager/vanilla ölüm anında (öldüğü an) hangi kalede olduğunu not eder - respawn'da kullanılır. */
    public void olumNotEt(Player p) {
        for (VahsiKale kale : kaleler.values()) {
            if (!kale.fethedildi && kale.kusatmadakiOyuncular.contains(p.getUniqueId())) {
                kaleOlumYeri.put(p.getUniqueId(), kale);
                Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
                if (klan != null) {
                    klan.prestij = Math.max(0, klan.prestij - olumPrestijKaybi());
                    plugin.veri().kaydet();
                    plugin.klanManager().klanaGonder(klan, "vahsikale-olum-prestij",
                            "&c{oyuncu} &7bir Vahşi Kalede öldü, klan &e-{kayip} prestij&7 kaybetti.",
                            "oyuncu", p.getName(), "kayip", olumPrestijKaybi());
                }
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onKomutEngeli(PlayerCommandPreprocessEvent event) {
        String komut = event.getMessage().toLowerCase(Locale.ROOT).split(" ")[0].substring(1);
        if (!komut.equals("home") && !komut.equals("back") && !komut.equals("tpa") && !komut.equals("tpaccept")
                && !komut.equals("tpyes") && !komut.equals("spawn")) return;
        Player p = event.getPlayer();
        for (VahsiKale kale : kaleler.values()) {
            if (!kale.fethedildi && kale.kusatmadakiOyuncular.contains(p.getUniqueId())) {
                event.setCancelled(true);
                p.sendMessage(Mesaj.renk("&cKuşatma altındayken bu komutu kullanamazsın! Önce kale alanından çık."));
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEtkilesim(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Elçi NPC (ticaret/görev/barış) bu sürümde YOK - ileride eklenecek. Şimdilik sadece sandık kilidi geçerli.
    }

    // ------------------------------------------------------------------ SQLITE
    private void baglan() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            Class.forName("me.klansistemi.shaded.sqlite.JDBC");
            baglanti = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "vahsikaleler.db'ye bağlanılamadı! Vahşi Kale sistemi devre dışı kalabilir.", e);
        }
    }

    private void tablolariOlustur() {
        if (baglanti == null) return;
        try (Statement st = baglanti.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS kaleler (id TEXT PRIMARY KEY, isim TEXT, seviye INTEGER, " +
                    "merkez TEXT, giris TEXT, tespit_yaricap REAL, saldiri_yaricap REAL, " +
                    "fethedildi INTEGER DEFAULT 0, fetheden_klan TEXT, fetih_zamani INTEGER DEFAULT 0, prestij_verildi INTEGER DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS nobetciler (kale_id TEXT, npc_uuid TEXT, rol TEXT, oldu INTEGER DEFAULT 0, " +
                    "PRIMARY KEY (kale_id, npc_uuid))");
            st.execute("CREATE TABLE IF NOT EXISTS sandiklar (kale_id TEXT, konum TEXT, acildi INTEGER DEFAULT 0, " +
                    "PRIMARY KEY (kale_id, konum))");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "vahsikaleler.db tabloları oluşturulamadı!", e);
        }
    }

    private void kaydet(VahsiKale kale) {
        if (baglanti == null) return;
        plugin.arkaPlanda(() -> {
            try (PreparedStatement ps = baglanti.prepareStatement(
                    "INSERT INTO kaleler (id, isim, seviye, merkez, giris, tespit_yaricap, saldiri_yaricap, fethedildi, fetheden_klan, fetih_zamani, prestij_verildi) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET isim=excluded.isim, seviye=excluded.seviye, " +
                            "merkez=excluded.merkez, giris=excluded.giris, tespit_yaricap=excluded.tespit_yaricap, saldiri_yaricap=excluded.saldiri_yaricap, " +
                            "fethedildi=excluded.fethedildi, fetheden_klan=excluded.fetheden_klan, fetih_zamani=excluded.fetih_zamani, prestij_verildi=excluded.prestij_verildi")) {
                ps.setString(1, kale.id.toString());
                ps.setString(2, kale.isim);
                ps.setInt(3, kale.seviye);
                ps.setString(4, ArenaManager.konumYaz(kale.merkez));
                ps.setString(5, ArenaManager.konumYaz(kale.giris));
                ps.setDouble(6, kale.tespitYaricap);
                ps.setDouble(7, kale.saldiriYaricap);
                ps.setInt(8, kale.fethedildi ? 1 : 0);
                ps.setString(9, kale.fetihedenKlanId == null ? null : kale.fetihedenKlanId.toString());
                ps.setLong(10, kale.fetihZamani);
                ps.setInt(11, kale.prestijVerildi ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Vahşi Kale kaydedilemedi: " + kale.isim, e);
            }
        });
    }

    private void silDb(UUID id) {
        if (baglanti == null) return;
        plugin.arkaPlanda(() -> {
            try (PreparedStatement p1 = baglanti.prepareStatement("DELETE FROM kaleler WHERE id=?");
                 PreparedStatement p2 = baglanti.prepareStatement("DELETE FROM nobetciler WHERE kale_id=?");
                 PreparedStatement p3 = baglanti.prepareStatement("DELETE FROM sandiklar WHERE kale_id=?")) {
                p1.setString(1, id.toString()); p1.executeUpdate();
                p2.setString(1, id.toString()); p2.executeUpdate();
                p3.setString(1, id.toString()); p3.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Vahşi Kale silinemedi: " + id, e);
            }
        });
    }

    private void nobetciKaydet(UUID kaleId, UUID npcId, String rol, boolean oldu) {
        if (baglanti == null) return;
        plugin.arkaPlanda(() -> {
            try (PreparedStatement ps = baglanti.prepareStatement(
                    "INSERT INTO nobetciler (kale_id, npc_uuid, rol, oldu) VALUES (?,?,?,?) " +
                            "ON CONFLICT(kale_id, npc_uuid) DO UPDATE SET oldu=excluded.oldu")) {
                ps.setString(1, kaleId.toString());
                ps.setString(2, npcId.toString());
                ps.setString(3, rol);
                ps.setInt(4, oldu ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Nöbetçi kaydedilemedi", e);
            }
        });
    }

    private void nobetciOldu(UUID kaleId, UUID npcId) {
        if (baglanti == null) return;
        plugin.arkaPlanda(() -> {
            try (PreparedStatement ps = baglanti.prepareStatement(
                    "UPDATE nobetciler SET oldu=1 WHERE kale_id=? AND npc_uuid=?")) {
                ps.setString(1, kaleId.toString());
                ps.setString(2, npcId.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Nöbetçi ölüm durumu kaydedilemedi", e);
            }
        });
    }

    private void sandikKaydet(UUID kaleId, String konum, boolean acildi) {
        if (baglanti == null) return;
        plugin.arkaPlanda(() -> {
            try (PreparedStatement ps = baglanti.prepareStatement(
                    "INSERT INTO sandiklar (kale_id, konum, acildi) VALUES (?,?,?) " +
                            "ON CONFLICT(kale_id, konum) DO UPDATE SET acildi=excluded.acildi")) {
                ps.setString(1, kaleId.toString());
                ps.setString(2, konum);
                ps.setInt(3, acildi ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Sandık kaydedilemedi", e);
            }
        });
    }

    private void yukle() {
        if (baglanti == null) return;
        try (Statement st = baglanti.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM kaleler")) {
            while (rs.next()) {
                VahsiKale kale = new VahsiKale(UUID.fromString(rs.getString("id")));
                kale.isim = rs.getString("isim");
                kale.seviye = rs.getInt("seviye");
                kale.merkez = ArenaManager.konumOku(rs.getString("merkez"));
                kale.giris = ArenaManager.konumOku(rs.getString("giris"));
                kale.tespitYaricap = rs.getDouble("tespit_yaricap");
                kale.saldiriYaricap = rs.getDouble("saldiri_yaricap");
                kale.fethedildi = rs.getInt("fethedildi") == 1;
                String fklan = rs.getString("fetheden_klan");
                kale.fetihedenKlanId = fklan == null ? null : UUID.fromString(fklan);
                kale.fetihZamani = rs.getLong("fetih_zamani");
                kale.prestijVerildi = rs.getInt("prestij_verildi") == 1;
                if (kale.merkez == null) {
                    plugin.getLogger().warning("'" + kale.isim + "' Vahşi Kalesinin dünyası yüklü değil, şimdilik atlanıyor.");
                    continue;
                }
                kaleler.put(kale.id, kale);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Vahşi Kaleler yüklenemedi!", e);
            return;
        }
        try (PreparedStatement ps = baglanti.prepareStatement("SELECT * FROM nobetciler WHERE kale_id=?")) {
            for (VahsiKale kale : kaleler.values()) {
                ps.setString(1, kale.id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID npcId = UUID.fromString(rs.getString("npc_uuid"));
                        kale.nobetciRolleri.put(npcId, rs.getString("rol"));
                        if (rs.getInt("oldu") == 1) kale.oluNobetciler.add(npcId);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Vahşi Kale nöbetçileri yüklenemedi!", e);
        }
        try (PreparedStatement ps = baglanti.prepareStatement("SELECT * FROM sandiklar WHERE kale_id=?")) {
            for (VahsiKale kale : kaleler.values()) {
                ps.setString(1, kale.id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        kale.sandiklar.put(rs.getString("konum"), rs.getInt("acildi") == 1);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Vahşi Kale sandıkları yüklenemedi!", e);
        }
        if (!kaleler.isEmpty()) {
            plugin.getLogger().info(kaleler.size() + " Vahşi Kale yüklendi.");
            Bukkit.getScheduler().runTaskLater(plugin, this::disguiseleriTazele, 100L);
        }
    }

    public void kapat() {
        if (baglanti != null) {
            try { baglanti.close(); } catch (SQLException ignored) { }
        }
    }
}
