package me.klansistemi.vahsikale;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import me.klansistemi.KlanSistemi;
import me.klansistemi.Para;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;
import me.klansistemi.model.Rol;

/**
 * Faz 5: NPC klanlarının (Vahşi Kaleler) arka plan dünyası. Hepsi PERİYODİK/SOYUT kurallarla işler (gerçek
 * zamanlı ağır simülasyon yok):
 *  - Otonom NPC-NPC diplomasi: savaş / ittifak / ticaret (güç + hazine soyut değerleri)
 *  - Soyut kervan: kale başına bir kervan, periyodik "yolda olay" rulosu; yok olursa ENKAZ kaydı
 *  - Enkaz: oyuncu 70 blok yaklaşınca tek seferlik sandık (lazy), yaklaştıkça süre SIFIRLANIR
 *  - Hancı dedikodusu: /npcklan dedikodu (bahşiş, cooldown) - koordinat verilmez, 8 yön cümlesi
 *  - Oyuncu klanı ↔ NPC klanı: tanışma, saygı, görev (haraç/eşya), ittifak (barış), ücretli takviye
 * Varsayımlar (kullanıcıya raporlandı): hazine/güç soyut sayıdır (gerçek eşya değil); patika ve köprü
 * (kalıcı dünya değişikliği) bu sürümde YOK.
 */
public class NpcDunyaYonetici implements Listener {

    private static final String[] YON = {"kuzey", "kuzeydoğu", "doğu", "güneydoğu", "güney", "güneybatı", "batı", "kuzeybatı"};
    private static final String[] YER = {"büyük vadiden", "taşlı patikadan", "sık ormandan", "nehir kıyısından", "eski harabelerin yanından", "çorak ovadan"};

    private final KlanSistemi plugin;
    private final Random rng = new Random();
    private final File dosya;
    private boolean veriYuklendi = false;

    private final Map<UUID, Integer> guc = new LinkedHashMap<>();
    private final Map<UUID, Double> hazine = new LinkedHashMap<>(); // soyut PARA (haraç/ticaret)
    private final Map<UUID, List<ItemStack>> hazineEsya = new LinkedHashMap<>(); // GERÇEK eşyalar (kervan yağması)
    private final Map<UUID, String[]> zindanGorev = new LinkedHashMap<>(); // klan -> {kaleId, zindanId, zindanIsim, dunya, x, y, z}
    private final Map<String, String> iliski = new LinkedHashMap<>(); // "a|b" (sıralı) -> ITTIFAK | SAVAS
    private final Map<UUID, Map<UUID, Integer>> saygi = new LinkedHashMap<>(); // klan -> kale -> saygı (anahtar var = tanışıldı)
    private final Map<UUID, long[]> takviye = new LinkedHashMap<>(); // klan -> {gunNo, sayi, sonZaman}
    private final Map<UUID, Map<UUID, Long>> ittifaklar = new LinkedHashMap<>(); // klan -> kale -> bitiş (KALICI)
    private final Map<UUID, Long> dedikoduSon = new LinkedHashMap<>();
    private final Map<UUID, Kervan> kervanlar = new LinkedHashMap<>();
    private final Map<UUID, Enkaz> enkazlar = new LinkedHashMap<>();

    private static class Kervan {
        double x, z; boolean enkaz; final List<ItemStack> esya = new ArrayList<>();
        String kaynakKey, hedefKey; double kx, kz; boolean koyGordu;
    }

    private static class Enkaz {
        UUID kaleId; String dunya; double x, z; final List<ItemStack> esya = new ArrayList<>();
        long sonYakinlik; boolean sandikAcildi; boolean sandikYerlesti;
        int sx, sy, sz;
        final List<String> sahne = new ArrayList<>(); // "x;y;z;konulanMalzeme;ilkBlokVerisi"
        boolean sahneHazir;
    }

    public NpcDunyaYonetici(KlanSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "npc_dunya.yml");
        yukle();
    }

    // ------------------------------------------------------------------ AYAR
    private int dk(String yol, int v) { return plugin.getConfig().getInt("npc." + yol, v); }
    private boolean aktif() { return plugin.getConfig().getBoolean("npc.aktif", true); }

    private static String anahtar(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) < 0 ? a + "|" + b : b + "|" + a;
    }

    private int gucu(VahsiKale k) { return guc.computeIfAbsent(k.id, i -> k.seviye * 10); }
    private double hazinesi(VahsiKale k) { return hazine.computeIfAbsent(k.id, i -> k.seviye * 200.0); }

    private List<VahsiKale> aktifKaleler() {
        List<VahsiKale> l = new ArrayList<>();
        for (VahsiKale k : plugin.vahsiKale().tumKaleler()) if (!k.fethedildi && k.merkez != null && k.merkez.getWorld() != null) l.add(k);
        return l;
    }

    // ------------------------------------------------------------------ OTONOM DİPLOMASİ
    /** Periyodik (npc.otonom-dakika). Rastgele iki aktif kale arasında soyut olay. */
    public void otonomTick() {
        if (!aktif() || !veriYuklendi) return;
        List<VahsiKale> l = aktifKaleler();
        if (l.size() < 2) return;
        VahsiKale a = l.get(rng.nextInt(l.size()));
        VahsiKale b = l.get(rng.nextInt(l.size()));
        if (a == b) return;
        String key = anahtar(a.id, b.id);
        String mevcut = iliski.get(key);
        double zar = rng.nextDouble();
        if ("SAVAS".equals(mevcut)) {
            savasCoz(a, b, key);
        } else if ("ITTIFAK".equals(mevcut)) {
            if (zar < 0.10) { iliski.remove(key); plugin.getLogger().info("[NPC] İttifak bozuldu: " + a.isim + " x " + b.isim); }
        } else if (zar < 0.35) {
            iliski.put(key, "SAVAS");
            plugin.getLogger().info("[NPC] Savaş ilan etti: " + a.isim + " x " + b.isim);
        } else if (zar < 0.65) {
            iliski.put(key, "ITTIFAK");
            plugin.getLogger().info("[NPC] İttifak kurdu: " + a.isim + " + " + b.isim);
        } else {
            // ticaret: ikisinin de hazinesi seviyeyle orantılı artar (aynı sunucu ekonomisine soyut bağlı)
            hazine.put(a.id, hazinesi(a) + 40.0 * a.seviye);
            hazine.put(b.id, hazinesi(b) + 40.0 * b.seviye);
        }
        kaydet();
    }

    /** Savaş sonucu: güce göre olasılık. Kazanan kaybedenin hazinesinin %25'ini ATOMİK alır (tek blokta, çift sayım yok). */
    private void savasCoz(VahsiKale a, VahsiKale b, String key) {
        synchronized (this) {
            double pa = gucu(a) / (double) (gucu(a) + gucu(b));
            VahsiKale kazanan = rng.nextDouble() < pa ? a : b;
            VahsiKale kaybeden = kazanan == a ? b : a;
            double ganimet = Math.floor(hazinesi(kaybeden) * 0.25);
            hazine.put(kaybeden.id, hazinesi(kaybeden) - ganimet);
            hazine.put(kazanan.id, hazinesi(kazanan) + ganimet);
            List<ItemStack> alinan = hazineAl(kaybeden.id, 0.25); // gerçek eşya ganimeti: önce kaybedenden ÇIKAR, sonra kazanana EKLE
            hazineyeEkle(kazanan.id, alinan);
            guc.put(kazanan.id, gucu(kazanan) + 3);
            guc.put(kaybeden.id, Math.max(1, gucu(kaybeden) - 2));
            iliski.remove(key);
            plugin.getLogger().info("[NPC] Savaş bitti: " + kazanan.isim + " kazandı, " + kaybeden.isim + "'dan " + Math.round(ganimet) + " ganimet aldı.");
        }
    }

    private static int deger(Material m) {
        return switch (m) {
            case IRON_INGOT -> 1; case GOLD_INGOT -> 2; case EMERALD -> 4; case DIAMOND -> 5; case NETHERITE_SCRAP -> 8; case NETHERITE_INGOT -> 30;
            default -> 0;
        };
    }

    /** Hazinedeki GERÇEK değerli eşyalar tüketilerek güç artar (silah/zırh yapımı soyutlaması). Üst sınır yok (karar). */
    private void gelisim(VahsiKale k) {
        List<ItemStack> l = hazineEsya.get(k.id);
        if (l == null) return;
        int esik = 20 * k.seviye, toplam = 0;
        for (ItemStack it : l) toplam += deger(it.getType()) * it.getAmount();
        if (toplam < esik) return;
        int harca = esik;
        for (java.util.Iterator<ItemStack> i = l.iterator(); i.hasNext() && harca > 0; ) {
            ItemStack it = i.next();
            int d = deger(it.getType());
            if (d == 0) continue;
            int adet = Math.min(it.getAmount(), (int) Math.ceil(harca / (double) d));
            harca -= adet * d;
            if (adet >= it.getAmount()) i.remove(); else it.setAmount(it.getAmount() - adet);
        }
        guc.put(k.id, gucu(k) + 1);
    }

    private static final int HAZINE_STACK_SINIRI = 300;

    private synchronized void hazineyeEkle(UUID kale, List<ItemStack> eklenecek) {
        List<ItemStack> l = hazineEsya.computeIfAbsent(kale, i -> new ArrayList<>());
        for (ItemStack e : eklenecek) {
            if (e == null || e.getType().isAir()) continue;
            ItemStack kalan = e.clone();
            for (ItemStack mevcut : l) {
                if (kalan.getAmount() <= 0) break;
                if (mevcut.isSimilar(kalan) && mevcut.getAmount() < mevcut.getMaxStackSize()) {
                    int tasi = Math.min(kalan.getAmount(), mevcut.getMaxStackSize() - mevcut.getAmount());
                    mevcut.setAmount(mevcut.getAmount() + tasi);
                    kalan.setAmount(kalan.getAmount() - tasi);
                }
            }
            if (kalan.getAmount() > 0 && l.size() < HAZINE_STACK_SINIRI) l.add(kalan);
        }
    }

    /** Hazineden yaklaşık oran kadar stack'i ÇIKARIR ve döndürür (çıkarma tek senkron blokta - çift kullanım olmaz). */
    private synchronized List<ItemStack> hazineAl(UUID kale, double oran) {
        List<ItemStack> l = hazineEsya.get(kale);
        List<ItemStack> cikan = new ArrayList<>();
        if (l == null || l.isEmpty()) return cikan;
        int n = (int) Math.max(1, Math.floor(l.size() * oran));
        for (int i = 0; i < n && !l.isEmpty(); i++) cikan.add(l.remove(rng.nextInt(l.size())));
        return cikan;
    }

    private List<ItemStack> lootUret(boolean hayvan, int seviye) {
        List<ItemStack> l = new ArrayList<>();
        if (hayvan) {
            Material[] h = {Material.LEATHER, Material.BEEF, Material.PORKCHOP, Material.BONE, Material.STRING, Material.FEATHER};
            for (int i = 0; i < 2 + rng.nextInt(3); i++) l.add(new ItemStack(h[rng.nextInt(h.length)], 2 + rng.nextInt(5)));
        } else {
            Material[] t = {Material.WHEAT, Material.BREAD, Material.COAL, Material.ARROW, Material.PAPER, Material.IRON_INGOT, Material.EMERALD};
            for (int i = 0; i < 2 + rng.nextInt(3); i++) l.add(new ItemStack(t[rng.nextInt(t.length)], 1 + rng.nextInt(5)));
            if (seviye >= 2 && rng.nextDouble() < 0.4) l.add(new ItemStack(Material.GOLD_INGOT, 1 + rng.nextInt(3)));
            if (seviye >= 3 && rng.nextDouble() < 0.25) l.add(new ItemStack(Material.DIAMOND, 1 + rng.nextInt(2)));
            if (seviye >= 5 && rng.nextDouble() < 0.10) l.add(new ItemStack(Material.NETHERITE_SCRAP, 1));
        }
        return l;
    }

    /** Fetihte: kalenin hazine eşyaları kalenin yanındaki sandık(lar)a konur - oyuncu yağmalar. Önce hazineden ÇIKARILIR ve kaydedilir. */
    public void fetihDagit(VahsiKale kale) {
        if (!veriYuklendi) return;
        List<ItemStack> l;
        synchronized (this) { l = hazineEsya.remove(kale.id); }
        if (l == null || l.isEmpty() || kale.merkez == null || kale.merkez.getWorld() == null) return;
        kaydet();
        World w = kale.merkez.getWorld();
        int[][] ofs = {{3, 0}, {3, 2}, {3, -2}, {5, 0}, {-3, 0}, {-3, 2}};
        int idx = 0, yerlesen = 0;
        List<ItemStack> kalan = new ArrayList<>(l);
        for (int[] o : ofs) {
            if (kalan.isEmpty()) break;
            int x = kale.merkez.getBlockX() + o[0], z = kale.merkez.getBlockZ() + o[1];
            Block b = w.getBlockAt(x, w.getHighestBlockYAt(x, z) + 1, z);
            if (!b.getType().isAir()) continue;
            b.setType(Material.CHEST);
            if (b.getState(false) instanceof Chest c) {
                java.util.Iterator<ItemStack> it = kalan.iterator();
                int n = 0;
                while (it.hasNext() && n < 27) { c.getInventory().addItem(it.next()); it.remove(); n++; }
                c.update(true, false);
                yerlesen++;
            }
        }
        for (ItemStack k : kalan) w.dropItemNaturally(kale.merkez, k); // yer bulunamadıysa yere dök (kayıp yok)
        plugin.getLogger().info("[NPC] " + kale.isim + " hazine odası yağmaya açıldı: " + l.size() + " stack, " + yerlesen + " sandık.");
    }

    /** Nöbetçi ölünce hazineden 1 stack yere düşer (loot yere düşer, otomatik kimseye gitmez - karar). */
    public void nobetciOlduLoot(VahsiKale kale, Location konum) {
        if (!veriYuklendi || konum.getWorld() == null || rng.nextDouble() > 0.35) return;
        List<ItemStack> al = hazineAl(kale.id, 0.02);
        for (ItemStack it : al) konum.getWorld().dropItemNaturally(konum, it);
        if (!al.isEmpty()) kaydet();
    }

    // ------------------------------------------------------------------ KERVAN
    private record Dugum(String key, double x, double z, boolean koy) { }

    private List<Dugum> dugumler(World w) {
        List<Dugum> l = new ArrayList<>();
        for (VahsiKale k : aktifKaleler()) {
            if (k.merkez.getWorld().equals(w)) l.add(new Dugum("kale:" + k.id, k.merkez.getX(), k.merkez.getZ(), false));
        }
        double[] koy = YasakAlan.koyDikdortgeniGetir();
        String kd = YasakAlan.koyDunyasiGetir();
        if (koy != null && kd != null && kd.equals(w.getName())) l.add(new Dugum("koy", (koy[0] + koy[1]) / 2, (koy[2] + koy[3]) / 2, true));
        return l;
    }

    private static Dugum dugumBul(List<Dugum> l, String key) {
        if (key == null) return null;
        for (Dugum d : l) if (d.key().equals(key)) return d;
        return null;
    }

    private static String rotaAnahtari(String a, String b) { return a.compareTo(b) < 0 ? a + "~" + b : b + "~" + a; }

    /** Sabit rotalar: en yakin dugumden en yakin dugume (kale/koy). Rotalar tekrar edildikce yol/kopru olusur. */
    private void hedefSec(Kervan kv, VahsiKale k, List<Dugum> dugumler) {
        String home = "kale:" + k.id;
        if (kv.esya.size() >= 8 && !home.equals(kv.kaynakKey) && dugumBul(dugumler, home) != null) { kv.hedefKey = home; return; }
        Dugum koy = dugumBul(dugumler, "koy");
        if (!kv.koyGordu && koy != null && !"koy".equals(kv.kaynakKey)) { kv.hedefKey = "koy"; return; }
        List<Dugum> aday = new ArrayList<>();
        for (Dugum d : dugumler) if (!d.key().equals(kv.kaynakKey)) aday.add(d);
        if (aday.isEmpty()) { kv.hedefKey = null; return; }
        final double cx = kv.x, cz = kv.z;
        aday.sort(java.util.Comparator.comparingDouble(d -> Math.hypot(d.x() - cx, d.z() - cz)));
        kv.hedefKey = aday.get(rng.nextInt(Math.min(3, aday.size()))).key();
    }

    /** Periyodik (npc.kervan-dakika): her aktif kale için soyut yolculuk + olay rulosu. */
    public void kervanTick() {
        if (!aktif() || !veriYuklendi) return;
        for (VahsiKale k : aktifKaleler()) {
            gelisim(k);
            World w = k.merkez.getWorld();
            Kervan kv = kervanlar.computeIfAbsent(k.id, i -> {
                Kervan n = new Kervan(); n.x = k.merkez.getX(); n.z = k.merkez.getZ(); return n;
            });
            if (kv.enkaz) continue;
            String home = "kale:" + k.id;
            if (kv.kaynakKey == null) { kv.kaynakKey = home; kv.kx = k.merkez.getX(); kv.kz = k.merkez.getZ(); }
            List<Dugum> dugumler = dugumler(w);
            if (kv.hedefKey == null) hedefSec(kv, k, dugumler);
            Dugum hedef = dugumBul(dugumler, kv.hedefKey);
            if (hedef == null) { kv.hedefKey = null; continue; }
            double kalan = Math.hypot(hedef.x() - kv.x, hedef.z() - kv.z);
            double adim = dk("kervan-adim", 350);
            if (kalan <= adim) {
                // VARIS: rota sayaci (patika/kopru), yuk teslimi / ticaret / hanci
                kv.x = hedef.x(); kv.z = hedef.z();
                plugin.patika().rotaEkle(w, rotaAnahtari(kv.kaynakKey, hedef.key()), (int) kv.kx, (int) kv.kz, (int) hedef.x(), (int) hedef.z());
                kv.kaynakKey = hedef.key(); kv.kx = hedef.x(); kv.kz = hedef.z(); kv.hedefKey = null;
                if (hedef.key().equals(home)) {
                    List<ItemStack> getirilen;
                    synchronized (this) { getirilen = new ArrayList<>(kv.esya); kv.esya.clear(); }
                    hazineyeEkle(k.id, getirilen); // yük önce kervandan ÇIKAR, sonra hazineye eklenir
                } else {
                    kv.esya.addAll(lootUret(false, k.seviye)); // köy/kale ticareti
                    if (hedef.koy()) {
                        kv.koyGordu = true;
                        plugin.hanci().koyaHanciKur("koy", w); // kervan köye ulaştı: hancı yoksa kurulur
                    }
                }
                continue;
            }
            // yolda ilerle (dogrusal rota)
            kv.x += (hedef.x() - kv.x) / kalan * adim;
            kv.z += (hedef.z() - kv.z) / kalan * adim;
            double olay = rng.nextDouble();
            if (olay < 0.30) {          // hayvan/yaratık saldırısı: nöbetçi gücüne göre loot
                if (rng.nextDouble() < Math.min(0.9, 0.5 + gucu(k) / 200.0)) kv.esya.addAll(lootUret(true, k.seviye));
            } else if (olay < 0.33) {   // pusu: kervan yok oldu -> ENKAZ
                kv.enkaz = true;
                Enkaz e = new Enkaz();
                e.kaleId = k.id; e.dunya = w.getName(); e.x = kv.x; e.z = kv.z;
                e.sonYakinlik = System.currentTimeMillis();
                // yükün yarısı enkaza kalır (sandığa gerçek eşya olarak), yarısı kaybolur
                for (int i = 0; i < kv.esya.size(); i++) if (i % 2 == 0) e.esya.add(kv.esya.get(i));
                if (e.esya.size() < 3) e.esya.addAll(lootUret(false, k.seviye)); // yük azsa bile sandık boş kalmasın
                enkazlar.put(UUID.randomUUID(), e);
                kv.esya.clear();
                // kervan ertesi dönemde yeniden kurulur
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    kv.enkaz = false; kv.x = k.merkez.getX(); kv.z = k.merkez.getZ();
                    kv.kaynakKey = home; kv.kx = kv.x; kv.kz = kv.z; kv.hedefKey = null;
                }, 20L * 60 * 30);
                plugin.getLogger().info("[NPC] " + k.isim + " kervanı pusuya düştü -> ENKAZ (" + Math.round(e.x) + "," + Math.round(e.z) + ")");
            }
        }
        kaydet();
    }

    /** ~3 sn'de bir: oyuncu yakınlığı (lazy spawn + süre sıfırlama + süre dolumu). */
    public void enkazKontrol() {
        if (!aktif() || !veriYuklendi || enkazlar.isEmpty()) return;
        long omur = dk("enkaz-omur-dakika", 40) * 60_000L;
        long simdi = System.currentTimeMillis();
        List<UUID> silinecek = new ArrayList<>();
        for (Map.Entry<UUID, Enkaz> en : enkazlar.entrySet()) {
            Enkaz e = en.getValue();
            World w = Bukkit.getWorld(e.dunya);
            if (w == null) continue;
            boolean yakin = false;
            for (Player p : w.getPlayers()) {
                double dx = p.getLocation().getX() - e.x, dz = p.getLocation().getZ() - e.z;
                if (dx * dx + dz * dz <= 70.0 * 70.0) { yakin = true; break; }
            }
            if (yakin) {
                e.sonYakinlik = simdi; // oyuncu yaklaştıkça süre SIFIRLANIR (karar)
                if (!e.sandikYerlesti && !e.sandikAcildi) sandikYerlestir(e, w);
            }
            if (simdi - e.sonYakinlik > omur || e.sandikAcildi && !yakin) silinecek.add(en.getKey());
        }
        for (UUID id : silinecek) { sandikTemizle(enkazlar.get(id)); enkazlar.remove(id); }
        if (!silinecek.isEmpty()) kaydet();
    }

    private void sandikYerlestir(Enkaz e, World w) {
        int x = (int) Math.floor(e.x), z = (int) Math.floor(e.z);
        if (YasakAlan.engelliMi(w, x, z, plugin.getConfig().getInt("vahsikale.yasak-alan-yaricap", 80))) { e.sandikAcildi = true; return; } // korumalı bölgede yerleşme
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = w.getHighestBlockYAt(x, z) + 1;
        Block b = w.getBlockAt(x, y, z);
        if (!b.getType().isAir()) return;
        if (e.esya.isEmpty()) { // eski kayıtlardan kalan boş enkaz: yine de ganimet olsun
            VahsiKale kk = plugin.vahsiKale().kaleBul(e.kaleId);
            e.esya.addAll(lootUret(false, kk != null ? kk.seviye : 1));
        }
        b.setType(Material.CHEST);
        if (b.getState(false) instanceof Chest c) {
            for (ItemStack it : e.esya) c.getInventory().addItem(it); // GERÇEK kervan yükü, tek seferlik
            e.esya.clear(); // artık sandıkta; tekrar yerleşirse boş
            c.update(true, false);
        }
        e.sx = x; e.sy = y; e.sz = z; e.sandikYerlesti = true;
        try { sahneKur(e, w, x, y, z); } catch (Exception ex) { plugin.getLogger().log(java.util.logging.Level.WARNING, "[NPC] enkaz sahnesi kurulamadı", ex); }
        kaydet();
    }

    private final org.bukkit.NamespacedKey enkazHayvanKey() { return new org.bukkit.NamespacedKey(plugin, "npc-enkaz-hayvan"); }

    /** "Savaş döndü" görünümü: etrafa kırık çitler, devrilmiş yük parçaları, yırtık bez ve yaralı yük hayvanları.
     *  Konan her bloğun önceki hali kaydedilir; enkaz silinince geri yüklenir. */
    private void sahneKur(Enkaz e, World w, int cx, int cy, int cz) {
        if (e.sahneHazir) return;
        e.sahneHazir = true;
        Material[] parca = {Material.OAK_FENCE, Material.OAK_FENCE, Material.OAK_FENCE, Material.OAK_FENCE, Material.OAK_FENCE,
                Material.OAK_SLAB, Material.OAK_SLAB, Material.OAK_TRAPDOOR, Material.BROWN_CARPET, Material.WHITE_CARPET, Material.COBWEB};
        int hedef = 8 + rng.nextInt(4), konan = 0, deneme = 0;
        while (konan < hedef && deneme++ < 60) {
            int x = cx + rng.nextInt(11) - 5, z = cz + rng.nextInt(11) - 5;
            if ((x == cx && z == cz) || !w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z);
            Block zemin = w.getBlockAt(x, y, z);
            if (!zemin.getType().isSolid() || zemin.isLiquid()) continue;
            Block ust = w.getBlockAt(x, y + 1, z);
            if (!(ust.getType().isAir() || ust.getType() == Material.SHORT_GRASS)) continue;
            Material m = parca[rng.nextInt(parca.length)];
            String ilk = ust.getBlockData().getAsString();
            ust.setType(m, false);
            if (m == Material.OAK_SLAB || m == Material.OAK_TRAPDOOR) { /* varsayılan veri yeterli: devrilmiş parça */ }
            e.sahne.add(x + ";" + (y + 1) + ";" + z + ";" + m.name() + ";" + ilk);
            konan++;
        }
        // yaralı yük hayvanları (ceset yok; sağ ve ayakta ama zayıf)
        org.bukkit.entity.EntityType[] hayvan = {org.bukkit.entity.EntityType.DONKEY, org.bukkit.entity.EntityType.MULE, org.bukkit.entity.EntityType.LLAMA};
        int adet = 1 + rng.nextInt(2);
        for (int i = 0; i < adet; i++) {
            Location l = new Location(w, cx + 0.5 + rng.nextInt(5) - 2, cy, cz + 0.5 + rng.nextInt(5) - 2);
            if (!w.getBlockAt(l).getType().isAir()) l = new Location(w, cx + 0.5, cy + 1, cz + 3.5);
            org.bukkit.entity.Entity h = w.spawnEntity(l, hayvan[rng.nextInt(hayvan.length)]);
            h.setPersistent(false); // chunk boşalınca kendiliğinden gider
            h.getPersistentDataContainer().set(enkazHayvanKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            if (h instanceof org.bukkit.entity.LivingEntity le) {
                var mx = le.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                double max = mx != null ? mx.getValue() : 20;
                le.setHealth(Math.max(1, max * 0.35));
                le.setCustomName(ChatColor.GRAY + "Yaralı yük hayvanı");
            }
        }
    }

    private void sahneTemizle(Enkaz e, World w) {
        for (String kayit : e.sahne) {
            try {
                String[] a = kayit.split(";", 5);
                int x = Integer.parseInt(a[0]), y = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue; // yüklü değil: kalıntı zararsız, atlanır
                Block b = w.getBlockAt(x, y, z);
                if (b.getType().name().equals(a[3])) b.setBlockData(Bukkit.createBlockData(a[4]), false);
            } catch (Exception ignored) { }
        }
        e.sahne.clear();
        for (org.bukkit.entity.Entity en : w.getNearbyEntities(new Location(w, e.x, e.sy, e.z), 20, 20, 20))
            if (en.getPersistentDataContainer().has(enkazHayvanKey(), org.bukkit.persistence.PersistentDataType.BYTE)) en.remove();
    }

    private void sandikTemizle(Enkaz e) {
        if (e == null || !e.sandikYerlesti) return;
        World w = Bukkit.getWorld(e.dunya);
        if (w == null) return;
        sahneTemizle(e, w);
        Block b = w.getBlockAt(e.sx, e.sy, e.sz);
        if (b.getType() == Material.CHEST) {
            if (b.getState(false) instanceof Chest c) c.getInventory().clear(); // eşya yere dökülmez - dupe/çöp yok
            b.setType(Material.AIR); // yerleştirmeden önce hava olduğu doğrulanmıştı -> geri alma = hava
        }
    }

    @EventHandler
    public void onSandikAc(org.bukkit.event.inventory.InventoryCloseEvent ev) {
        if (!(ev.getInventory().getHolder() instanceof Chest c)) return;
        Location l = c.getLocation();
        for (Enkaz e : enkazlar.values()) {
            if (e.sandikYerlesti && l.getBlockX() == e.sx && l.getBlockY() == e.sy && l.getBlockZ() == e.sz && l.getWorld().getName().equals(e.dunya)) {
                boolean bos = true;
                for (ItemStack it : ev.getInventory().getContents()) if (it != null && !it.getType().isAir()) { bos = false; break; }
                if (bos) e.sandikAcildi = true; // boşalınca bir sonraki kontrolde temizlenir
            }
        }
    }

    // ------------------------------------------------------------------ HANCI DEDİKODUSU
    public void dedikodu(Player p) {
        long cd = dk("dedikodu-cooldown-dakika", 12) * 60_000L;
        long simdi = System.currentTimeMillis();
        Long son = dedikoduSon.get(p.getUniqueId());
        if (son != null && simdi - son < cd) {
            p.sendMessage(ChatColor.RED + "Hancı şimdilik yeni bir şey duymadı. " + ((cd - (simdi - son)) / 60000 + 1) + " dk sonra tekrar sor.");
            return;
        }
        double bahsis = dk("dedikodu-bahsis", 200);
        // hedef: dünyadaki canlı bir kervan
        Kervan hedef = null; VahsiKale hk = null; double enYakin = Double.MAX_VALUE;
        for (VahsiKale k : aktifKaleler()) {
            Kervan kv = kervanlar.get(k.id);
            if (kv == null || kv.enkaz || !k.merkez.getWorld().equals(p.getWorld())) continue;
            double d = Math.hypot(kv.x - p.getLocation().getX(), kv.z - p.getLocation().getZ());
            if (d < enYakin) { enYakin = d; hedef = kv; hk = k; }
        }
        if (hedef == null) { p.sendMessage(ChatColor.GRAY + "Hancı omuz silkti: \"Bu aralar yoldan kervan geçmiyor.\""); return; }
        if (!plugin.meslek().bankadanCek(p.getUniqueId(), bahsis)) {
            p.sendMessage(ChatColor.RED + "Bahşiş için bankada yeterli bakiye yok (" + Para.yaz(bahsis) + ")."); return;
        }
        dedikoduSon.put(p.getUniqueId(), simdi);
        plugin.getLogger().info("[NPC] Dedikodu bahşişi: " + p.getName() + " " + Para.yaz(bahsis));
        double dx = hedef.x - p.getLocation().getX(), dz = hedef.z - p.getLocation().getZ();
        double aci = Math.toDegrees(Math.atan2(dx, -dz)); // kuzey = -Z
        int idx = (int) Math.round(((aci % 360) + 360) % 360 / 45.0) % 8;
        p.sendMessage(ChatColor.GOLD + "Hancı fısıldadı: " + ChatColor.YELLOW + "\"" + hk.isim + " Klanı'nın kervanı " + YON[idx] + "daki "
                + YER[rng.nextInt(YER.length)] + " geçiyorlarmış...\"");
        // koordinat/mesafe KESİNLİKLE verilmez
    }

    // ------------------------------------------------------------------ OYUNCU KLANI ↔ NPC KLANI
    private Map<UUID, Integer> saygilar(Klan k) { return saygi.computeIfAbsent(k.id, i -> new LinkedHashMap<>()); }

    /** Kuşatma/elçi/barış gibi ilk temasta tanışma kaydı. */
    public void tanis(Klan klan, VahsiKale kale) {
        if (klan == null || kale == null || !veriYuklendi) return;
        saygilar(klan).putIfAbsent(kale.id, 0);
    }

    /** Oyuncu klanı bir kaleye saldırınca: kalenin müttefiklerinin saygısı düşer, düşmanlarınınki artar (zincir etki). */
    public void saldiriBildir(Klan klan, VahsiKale kale) {
        if (klan == null || kale == null || !veriYuklendi) return;
        tanis(klan, kale);
        saygilar(klan).merge(kale.id, -20, Integer::sum);
        for (VahsiKale d : aktifKaleler()) {
            if (d == kale) continue;
            String durum = iliski.get(anahtar(kale.id, d.id));
            if (durum == null) continue;
            if (saygilar(klan).containsKey(d.id)) {
                saygilar(klan).merge(d.id, "ITTIFAK".equals(durum) ? -15 : 5, Integer::sum);
            }
        }
        kaydet();
    }

    // ------------------------------------------------------------------ İTTİFAK (kalıcı)
    public boolean ittifakVarMi(UUID klanId, UUID kaleId) {
        Map<UUID, Long> m;
        synchronized (this) { m = ittifaklar.get(klanId); }
        if (m == null) return false;
        Long b = m.get(kaleId);
        return b != null && b > System.currentTimeMillis();
    }

    private int seviyeOf(int saygiPuan) { return Math.max(0, saygiPuan / 50); }

    private static class Sahip implements InventoryHolder {
        final UUID klanId; UUID kaleId; // kaleId null = üst menü (klan listesi)
        final Map<Integer, UUID> slotKale = new LinkedHashMap<>();
        final Map<Integer, String> slotEylem = new LinkedHashMap<>();
        Inventory env;
        Sahip(UUID k) { klanId = k; }
        @Override public Inventory getInventory() { return env; }
    }

    private ItemStack oge(Material m, String ad, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ad);
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private void cerceve(Inventory env) {
        ItemStack cam = oge(Material.BLACK_STAINED_GLASS_PANE, " ", new ArrayList<>());
        for (int i = 0; i < env.getSize(); i++) if (env.getItem(i) == null) env.setItem(i, cam);
    }

    private boolean dostMu(VahsiKale k, UUID klanId) { return plugin.vahsiKale().dostMu(k, klanId); }

    /** ÜST MENÜ: tanışılan NPC klanları (kılıç simgesi + isim). Tıklayınca o klanın alt menüsü açılır. */
    public void panelAc(Player p) {
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (klan == null) { p.sendMessage(ChatColor.RED + "Bir klanda değilsiniz."); return; }
        Sahip s = new Sahip(klan.id);
        Inventory env = Bukkit.createInventory(s, 54, ChatColor.DARK_RED + "NPC Diplomasi Paneli");
        s.env = env;
        int slot = 0;
        for (Map.Entry<UUID, Integer> en : saygilar(klan).entrySet()) {
            VahsiKale k = plugin.vahsiKale().kaleBul(en.getKey());
            if (k == null || k.fethedildi || slot >= 45) continue;
            int sp = en.getValue();
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Seviye: " + ChatColor.WHITE + k.seviye + ChatColor.GRAY + "  Güç: " + ChatColor.WHITE + gucu(k));
            lore.add(ChatColor.GRAY + "Saygı: " + ChatColor.YELLOW + sp + ChatColor.GRAY + " (seviye " + seviyeOf(sp) + ")");
            lore.add(ChatColor.GRAY + "Durum: " + (dostMu(k, klan.id) ? ChatColor.GREEN + "İttifak" : ChatColor.RED + "Gergin"));
            lore.add("");
            lore.add(ChatColor.YELLOW + "► Görev, haraç, takviye ve ittifak için tıkla");
            env.setItem(slot, oge(Material.IRON_SWORD, ChatColor.GOLD + k.isim + ChatColor.GRAY + " (Sv " + k.seviye + ")", lore));
            s.slotKale.put(slot, k.id);
            slot++;
        }
        if (slot == 0) env.setItem(22, oge(Material.BARRIER, ChatColor.GRAY + "Henüz tanıştığınız NPC klanı yok",
                List.of(ChatColor.GRAY + "Bir Vahşi Kale'ye yaklaşınca", ChatColor.GRAY + "o klanla tanışırsınız.")));
        env.setItem(49, oge(Material.ARROW, ChatColor.YELLOW + "Klan Menüsüne Dön", List.of()));
        s.slotEylem.put(49, "ana");
        cerceve(env);
        p.openInventory(env);
    }

    /** ALT MENÜ: seçilen NPC klanıyla yapılabilecekler (hepsi fareyle). */
    private void altMenuAc(Player p, Klan klan, VahsiKale k) {
        Sahip s = new Sahip(klan.id);
        s.kaleId = k.id;
        Inventory env = Bukkit.createInventory(s, 27, ChatColor.DARK_RED + k.isim + " ► NPC Klanı");
        s.env = env;
        int sp = saygilar(klan).getOrDefault(k.id, 0);
        boolean dost = dostMu(k, klan.id);
        env.setItem(4, oge(Material.IRON_SWORD, ChatColor.GOLD + "" + ChatColor.BOLD + k.isim,
                List.of(ChatColor.GRAY + "Saygı: " + ChatColor.YELLOW + sp + ChatColor.GRAY + " (seviye " + seviyeOf(sp) + ")",
                        ChatColor.GRAY + "Güç: " + ChatColor.WHITE + gucu(k) + ChatColor.GRAY + "  Seviye: " + ChatColor.WHITE + k.seviye,
                        ChatColor.GRAY + "Durum: " + (dost ? ChatColor.GREEN + "İttifak" : ChatColor.RED + "Gergin"))));
        env.setItem(10, oge(Material.GOLD_INGOT, ChatColor.GOLD + "" + ChatColor.BOLD + "Haraç Öde",
                List.of(ChatColor.GRAY + "Klan kasasından " + ChatColor.GREEN + Para.yaz(haracUcreti(k)) + ChatColor.GRAY + " ödenir.",
                        ChatColor.GRAY + "Kazanç: " + ChatColor.YELLOW + "+15 saygı", ChatColor.DARK_GRAY + "(Lider/yardımcı)",
                        "", ChatColor.YELLOW + "► Tıkla")));
        s.slotEylem.put(10, "harac");
        env.setItem(11, oge(gorevEsya(k), ChatColor.AQUA + "" + ChatColor.BOLD + "Eşya Görevi",
                List.of(ChatColor.GRAY + "Teslim: " + ChatColor.WHITE + gorevMiktar(k) + " " + gorevEsya(k).name(),
                        ChatColor.GRAY + "Kazanç: " + ChatColor.YELLOW + "+25 saygı", "", ChatColor.YELLOW + "► Envanterinden teslim et")));
        s.slotEylem.put(11, "esya");
        String[] g = zindanGorev.get(klan.id);
        List<String> zl = new ArrayList<>();
        zl.add(ChatColor.GRAY + "Bir zindanın boss'unu düşürün.");
        zl.add(ChatColor.GRAY + "Kazanç: " + ChatColor.YELLOW + "+40 saygı");
        if (g != null) zl.add(ChatColor.GREEN + "Aktif: " + g[2] + " @ " + g[4] + ", " + g[5] + ", " + g[6]);
        zl.add(""); zl.add(ChatColor.YELLOW + "► " + (g != null ? "Görevi göster" : "Görev al"));
        env.setItem(12, oge(Material.WITHER_SKELETON_SKULL, ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Zindan Görevi", zl));
        s.slotEylem.put(12, "zindan");
        boolean tak = sp >= 50;
        env.setItem(14, oge(Material.IRON_HELMET, ChatColor.GREEN + "" + ChatColor.BOLD + "Takviye Çağır",
                List.of(ChatColor.GRAY + "5 dk boyunca " + ChatColor.WHITE + dk("takviye-muhafiz-sayisi", 5) + ChatColor.GRAY + " muhafız yanında savaşır.",
                        ChatColor.GRAY + "Ücret: " + ChatColor.GREEN + Para.yaz(takviyeUcreti(k)) + ChatColor.GRAY + " (kasa)",
                        ChatColor.GRAY + "Gerek: saygı ≥ 50 " + (tak ? ChatColor.GREEN + "✔" : ChatColor.RED + "✘"),
                        ChatColor.DARK_GRAY + "(Lider/yardımcı)", "", ChatColor.YELLOW + "► Tıkla")));
        s.slotEylem.put(14, "takviye");
        boolean itf = sp >= 100;
        env.setItem(15, oge(dost ? Material.LIME_BANNER : Material.WHITE_BANNER, ChatColor.WHITE + "" + ChatColor.BOLD + "İttifak Kur",
                List.of(ChatColor.GRAY + "7 gün: nöbetçiler saldırmaz, uyarı gelmez.",
                        ChatColor.GRAY + "Gerek: saygı ≥ 100 " + (itf ? ChatColor.GREEN + "✔" : ChatColor.RED + "✘"),
                        dost ? ChatColor.GREEN + "Şu an ittifak var (yenilemek için tıkla)." : "",
                        ChatColor.DARK_GRAY + "(Lider/yardımcı)", "", ChatColor.YELLOW + "► Tıkla")));
        s.slotEylem.put(15, "ittifak");
        env.setItem(22, oge(Material.ARROW, ChatColor.YELLOW + "Geri", List.of()));
        s.slotEylem.put(22, "geri");
        cerceve(env);
        p.openInventory(env);
    }

    private double haracUcreti(VahsiKale k) { return 500.0 * k.seviye; }
    private double takviyeUcreti(VahsiKale k) { return 1500.0 * k.seviye; }
    private Material gorevEsya(VahsiKale k) { return switch (k.seviye) { case 1 -> Material.IRON_INGOT; case 2 -> Material.GOLD_INGOT; case 3 -> Material.DIAMOND; case 4 -> Material.EMERALD; default -> Material.NETHERITE_SCRAP; }; }
    private int gorevMiktar(VahsiKale k) { return k.seviye >= 5 ? 4 : 8 - k.seviye; }

    @EventHandler
    public void onPanelTikla(InventoryClickEvent ev) {
        if (!(ev.getInventory().getHolder() instanceof Sahip s)) return;
        ev.setCancelled(true);
        if (!(ev.getWhoClicked() instanceof Player p)) return;
        if (ev.getClickedInventory() == null || ev.getClickedInventory() != ev.getInventory()) return;
        if (ev.getClick() != org.bukkit.event.inventory.ClickType.LEFT && ev.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) return;
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (klan == null || !klan.id.equals(s.klanId)) { p.closeInventory(); return; }
        int slot = ev.getRawSlot();
        if (s.kaleId == null) { // üst menü
            if ("ana".equals(s.slotEylem.get(slot))) { plugin.menu().anaMenu(p); return; }
            UUID kaleId = s.slotKale.get(slot);
            VahsiKale k = kaleId == null ? null : plugin.vahsiKale().kaleBul(kaleId);
            if (k != null && !k.fethedildi) altMenuAc(p, klan, k);
            return;
        }
        String eylem = s.slotEylem.get(slot);
        if (eylem == null) return;
        if ("geri".equals(eylem)) { panelAc(p); return; }
        VahsiKale k = plugin.vahsiKale().kaleBul(s.kaleId);
        if (k == null || k.fethedildi) { panelAc(p); return; }
        KlanUyesi ben = klan.uyeler.get(p.getUniqueId());
        boolean yetkili = ben != null && ben.rol != Rol.UYE;
        switch (eylem) {
            case "harac" -> { if (!yetkili) p.sendMessage(ChatColor.RED + "Haraç için lider/yardımcı olmalısın (kasadan ödenir)."); else harac(p, klan, k); }
            case "esya" -> esyaGorevi(p, klan, k);
            case "zindan" -> zindanGorevi(p, klan, k);
            case "takviye" -> { if (!yetkili) p.sendMessage(ChatColor.RED + "Bu işlem için lider/yardımcı olmalısın."); else takviyeCagir(p, klan, k); }
            case "ittifak" -> { if (!yetkili) p.sendMessage(ChatColor.RED + "Bu işlem için lider/yardımcı olmalısın."); else ittifakKur(p, klan, k); }
            default -> { }
        }
        if (p.getOpenInventory().getTopInventory().getHolder() == s) altMenuAc(p, klan, k); // güncel değerlerle yenile
    }

    // ------------------------------------------------------------------ ZİNDAN GÖREVİ (Arena-Ligi bağlantısı)
    /** Arena-Ligi'nin zindanlar.yml'sini (salt-okunur) okuyup temizlenmemiş zindanlardan birini görev verir. */
    private List<String[]> aktifZindanlar() {
        List<String[]> l = new ArrayList<>(); // {id, isim, dunya, x, y, z}
        org.bukkit.plugin.Plugin al = Bukkit.getPluginManager().getPlugin("ArenaLigi");
        if (al == null) return l;
        File f = new File(al.getDataFolder(), "zindanlar.yml");
        if (!f.exists()) return l;
        ConfigurationSection zs = YamlConfiguration.loadConfiguration(f).getConfigurationSection("zindanlar");
        if (zs == null) return l;
        for (String id : zs.getKeys(false)) {
            ConfigurationSection c = zs.getConfigurationSection(id);
            if (c == null || c.getBoolean("temizlendi", false)) continue;
            String[] m = String.valueOf(c.getString("merkez", "")).split(";");
            if (m.length < 4) continue;
            try { l.add(new String[]{id, c.getString("isim", "Zindan"), m[0], String.valueOf(Math.round(Double.parseDouble(m[1]))), String.valueOf(Math.round(Double.parseDouble(m[2]))), String.valueOf(Math.round(Double.parseDouble(m[3])))}); }
            catch (NumberFormatException ignored) { }
        }
        return l;
    }

    private void zindanGorevi(Player p, Klan klan, VahsiKale k) {
        if (saygilar(klan).getOrDefault(k.id, 0) < 0) { p.sendMessage(ChatColor.RED + k.isim + " seninle görev paylaşmıyor (saygı negatif)."); return; }
        List<String[]> aktif = aktifZindanlar();
        String[] mevcut = zindanGorev.get(klan.id);
        if (mevcut != null) {
            boolean hala = false;
            for (String[] z : aktif) if (z[0].equals(mevcut[1])) { hala = true; break; }
            if (hala) {
                p.sendMessage(ChatColor.GOLD + "Aktif zindan göreviniz: " + ChatColor.YELLOW + mevcut[2] + ChatColor.GRAY + " @ " + mevcut[3] + " " + mevcut[4] + ", " + mevcut[5] + ", " + mevcut[6]
                        + ChatColor.GRAY + " - boss'u düşürün.");
                return;
            }
            zindanGorev.remove(klan.id); // temizlendi/silindi: görev iptal
        }
        if (aktif.isEmpty()) { p.sendMessage(ChatColor.RED + "Şu an temizlenmemiş zindan yok, sonra tekrar dene."); return; }
        String[] z = aktif.get(rng.nextInt(aktif.size()));
        zindanGorev.put(klan.id, new String[]{k.id.toString(), z[0], z[1], z[2], z[3], z[4], z[5]});
        kaydet();
        p.sendMessage(ChatColor.GOLD + k.isim + " senden " + ChatColor.YELLOW + z[1] + ChatColor.GOLD + " zindanının temizlenmesini istiyor: "
                + ChatColor.WHITE + z[2] + " " + z[3] + ", " + z[4] + ", " + z[5] + ChatColor.GRAY + " (klanından biri boss'a hasar verip düşürürse görev tamamlanır).");
    }

    /** npczindan konsol komutu (Arena-Ligi boss ölünce gönderir): oyuncunun klanının görevi bu zindansa tamamlanır. */
    public void zindanTamamlandi(String oyuncuAdi, String zindanId) {
        if (!veriYuklendi) return;
        Player p = Bukkit.getPlayerExact(oyuncuAdi);
        if (p == null) return;
        Klan klan = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (klan == null) return;
        String[] g;
        synchronized (this) {
            g = zindanGorev.get(klan.id);
            if (g == null || !g[1].equals(zindanId)) return;
            zindanGorev.remove(klan.id); // önce kaldır -> aynı görev ikinci kez ödül vermez (birden çok katılımcı komutu)
        }
        VahsiKale k = plugin.vahsiKale().kaleBul(UUID.fromString(g[0]));
        if (k != null) { saygilar(klan).merge(k.id, 40, Integer::sum); guc.put(k.id, gucu(k) + 1); }
        kaydet();
        plugin.klanManager().klanaGonder(klan, "npc-zindan-gorev", "&a{kale} &7zindan görevi tamamlandı (&e{zindan}&7): &e+40 saygı&7.",
                "kale", k != null ? k.isim : "NPC klanı", "zindan", g[2]);
    }

    private void harac(Player p, Klan klan, VahsiKale k) {
        double u = haracUcreti(k);
        if (plugin.kasaKilitliMi(klan)) { p.sendMessage(ChatColor.RED + "Klan kasası şu an kilitli (savaşta)."); return; }
        if (klan.kasa < u) { p.sendMessage(ChatColor.RED + "Klan kasasında yeterli para yok (" + Para.yaz(u) + ")."); return; }
        klan.kasa = Para.kurus(klan.kasa - u);
        plugin.veri().kaydet();
        plugin.log().yaz(klan, p.getName(), "NPC HARAC", Para.yaz(u) + " -> " + k.isim + " (kasa: " + Para.yaz(klan.kasa) + ")");
        hazine.put(k.id, hazinesi(k) + u);
        saygilar(klan).merge(k.id, 15, Integer::sum);
        kaydet();
        p.sendMessage(ChatColor.GREEN + k.isim + " haracı kabul etti. +15 saygı.");
    }

    private void esyaGorevi(Player p, Klan klan, VahsiKale k) {
        Material m = gorevEsya(k); int n = gorevMiktar(k);
        if (!p.getInventory().containsAtLeast(new ItemStack(m), n)) { p.sendMessage(ChatColor.RED + "Envanterinde " + n + " " + m.name() + " yok."); return; }
        p.getInventory().removeItem(new ItemStack(m, n)); // önce al, sonra ödül (dupe yok)
        saygilar(klan).merge(k.id, 25, Integer::sum);
        guc.put(k.id, gucu(k) + 1);
        kaydet();
        p.sendMessage(ChatColor.GREEN + "Görev tamamlandı! +25 saygı.");
    }

    private void ittifakKur(Player p, Klan klan, VahsiKale k) {
        if (saygilar(klan).getOrDefault(k.id, 0) < 100) { p.sendMessage(ChatColor.RED + "İttifak için en az 100 saygı gerekir."); return; }
        long bitis = System.currentTimeMillis() + 7L * 24 * 3_600_000L;
        k.barisAnlasmalari.put(klan.id, bitis);
        synchronized (this) { ittifaklar.computeIfAbsent(klan.id, i -> new LinkedHashMap<>()).put(k.id, bitis); }
        kaydet(); // yeniden başlatmada kaybolmasın
        p.sendMessage(ChatColor.GREEN + k.isim + " ile 7 günlük ittifak kuruldu (nöbetçiler saldırmaz, uyarı gelmez).");
    }

    private void takviyeCagir(Player p, Klan klan, VahsiKale k) {
        if (saygilar(klan).getOrDefault(k.id, 0) < 50) { p.sendMessage(ChatColor.RED + "Takviye için en az 50 saygı gerekir."); return; }
        long gun = System.currentTimeMillis() / 86_400_000L, simdi = System.currentTimeMillis();
        long[] t = takviye.computeIfAbsent(klan.id, i -> new long[]{gun, 0, 0});
        if (t[0] != gun) { t[0] = gun; t[1] = 0; }
        if (t[1] >= dk("takviye-gunluk-limit", 2)) { p.sendMessage(ChatColor.RED + "Bugünkü takviye hakkın doldu."); return; }
        long cd = dk("takviye-cooldown-dakika", 30) * 60_000L;
        if (simdi - t[2] < cd) { p.sendMessage(ChatColor.RED + "Takviye için " + ((cd - (simdi - t[2])) / 60000 + 1) + " dk beklemelisin."); return; }
        double u = takviyeUcreti(k);
        if (plugin.kasaKilitliMi(klan) || klan.kasa < u) { p.sendMessage(ChatColor.RED + "Klan kasası yetersiz/kilitli (" + Para.yaz(u) + ")."); return; }
        klan.kasa = Para.kurus(klan.kasa - u);
        plugin.veri().kaydet();
        plugin.log().yaz(klan, p.getName(), "NPC TAKVIYE", Para.yaz(u) + " -> " + k.isim + " (kasa: " + Para.yaz(klan.kasa) + ")");
        t[1]++; t[2] = simdi;
        int adet = plugin.vahsiKale().takviyeMuhafizlariCagir(k, p, dk("takviye-muhafiz-sayisi", 5), 300);
        hazine.put(k.id, hazinesi(k) + u);
        kaydet();
        p.sendMessage(ChatColor.GREEN + k.isim + " " + adet + " muhafız gönderdi (5 dk).");
        p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
    }

    // ------------------------------------------------------------------ BİLGİ / KOMUT
    public void komut(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return; }
        String alt = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "panel";
        switch (alt) {
            case "dedikodu" -> dedikodu(p);
            case "patika" -> {
                if (!p.hasPermission("klan.admin")) { p.sendMessage(ChatColor.RED + "Yetkin yok."); return; }
                String a2 = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (a2.equals("sifirla")) plugin.patika().hepsiniGeriAl(p); else plugin.patika().bilgi(p);
            }
            case "hanci" -> {
                if (!p.hasPermission("klan.admin")) { p.sendMessage(ChatColor.RED + "Yetkin yok."); return; }
                String a2 = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (a2.equals("kur")) plugin.hanci().kur(p);
                else if (a2.equals("sil")) plugin.hanci().sil(p);
                else p.sendMessage(ChatColor.YELLOW + "/npcklan hanci <kur|sil>");
            }
            default -> panelAc(p);
        }
    }

    // ------------------------------------------------------------------ KALICILIK
    private void yukle() {
        try {
            if (dosya.exists()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
                ConfigurationSection g = y.getConfigurationSection("guc");
                if (g != null) for (String k : g.getKeys(false)) guc.put(UUID.fromString(k), g.getInt(k));
                ConfigurationSection h = y.getConfigurationSection("hazine");
                if (h != null) for (String k : h.getKeys(false)) hazine.put(UUID.fromString(k), h.getDouble(k));
                ConfigurationSection he = y.getConfigurationSection("hazine-esya");
                if (he != null) for (String k : he.getKeys(false)) {
                    List<ItemStack> li = new ArrayList<>();
                    for (Object o : he.getList(k, new ArrayList<>())) if (o instanceof ItemStack is) li.add(is);
                    hazineEsya.put(UUID.fromString(k), li);
                }
                ConfigurationSection zg = y.getConfigurationSection("zindan-gorev");
                if (zg != null) for (String k : zg.getKeys(false)) {
                    List<String> v = zg.getStringList(k);
                    if (v.size() == 7) zindanGorev.put(UUID.fromString(k), v.toArray(new String[0]));
                }
                ConfigurationSection it = y.getConfigurationSection("ittifak");
                if (it != null) for (String kl : it.getKeys(false)) {
                    ConfigurationSection m = it.getConfigurationSection(kl);
                    if (m == null) continue;
                    Map<UUID, Long> mm = new LinkedHashMap<>();
                    for (String ka : m.getKeys(false)) mm.put(UUID.fromString(ka), m.getLong(ka));
                    ittifaklar.put(UUID.fromString(kl), mm);
                }
                ConfigurationSection i = y.getConfigurationSection("iliski");
                if (i != null) for (String k : i.getKeys(false)) iliski.put(k.replace('_', '|'), i.getString(k));
                ConfigurationSection sg = y.getConfigurationSection("saygi");
                if (sg != null) for (String kl : sg.getKeys(false)) {
                    ConfigurationSection ks = sg.getConfigurationSection(kl);
                    if (ks == null) continue;
                    Map<UUID, Integer> m = new LinkedHashMap<>();
                    for (String ka : ks.getKeys(false)) m.put(UUID.fromString(ka), ks.getInt(ka));
                    saygi.put(UUID.fromString(kl), m);
                }
                ConfigurationSection kv = y.getConfigurationSection("kervan");
                if (kv != null) for (String k : kv.getKeys(false)) {
                    Kervan n = new Kervan();
                    for (Object o : kv.getList(k + ".esya", new ArrayList<>())) if (o instanceof ItemStack is) n.esya.add(is); n.x = kv.getDouble(k + ".x"); n.z = kv.getDouble(k + ".z"); n.enkaz = kv.getBoolean(k + ".enkaz");
                    n.kaynakKey = kv.getString(k + ".kaynak"); n.hedefKey = kv.getString(k + ".hedef"); n.kx = kv.getDouble(k + ".kx"); n.kz = kv.getDouble(k + ".kz"); n.koyGordu = kv.getBoolean(k + ".koygordu");
                    kervanlar.put(UUID.fromString(k), n);
                }
                ConfigurationSection en = y.getConfigurationSection("enkaz");
                if (en != null) for (String k : en.getKeys(false)) {
                    Enkaz e = new Enkaz();
                    e.kaleId = UUID.fromString(en.getString(k + ".kale"));
                    e.dunya = en.getString(k + ".dunya"); e.x = en.getDouble(k + ".x"); e.z = en.getDouble(k + ".z");
                    for (Object o : en.getList(k + ".esya", new ArrayList<>())) if (o instanceof ItemStack is) e.esya.add(is);
                    e.sonYakinlik = en.getLong(k + ".son"); e.sandikAcildi = en.getBoolean(k + ".acildi"); e.sandikYerlesti = en.getBoolean(k + ".yerlesti");
                    e.sx = en.getInt(k + ".sx"); e.sy = en.getInt(k + ".sy"); e.sz = en.getInt(k + ".sz");
                    e.sahne.addAll(en.getStringList(k + ".sahne")); e.sahneHazir = en.getBoolean(k + ".sahnehazir");
                    enkazlar.put(UUID.fromString(k), e);
                }
            }
            veriYuklendi = true; // dosya yok = ilk çalışma (boş başlangıç meşru); okuma hatası veriYuklendi'yi açmaz
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "npc_dunya.yml okunamadı - kayıt KAPALI (diskteki veri ezilmeyecek)", ex);
        }
    }

    public synchronized void kaydet() {
        if (!veriYuklendi) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            guc.forEach((k, v) -> y.set("guc." + k, v));
            hazine.forEach((k, v) -> y.set("hazine." + k, v));
            hazineEsya.forEach((k, v) -> y.set("hazine-esya." + k, v));
            zindanGorev.forEach((k, v) -> y.set("zindan-gorev." + k, java.util.Arrays.asList(v)));
            ittifaklar.forEach((kl, m) -> m.forEach((ka, v) -> y.set("ittifak." + kl + "." + ka, v)));
            iliski.forEach((k, v) -> y.set("iliski." + k.replace('|', '_'), v));
            saygi.forEach((kl, m) -> m.forEach((ka, v) -> y.set("saygi." + kl + "." + ka, v)));
            kervanlar.forEach((k, v) -> { y.set("kervan." + k + ".x", v.x); y.set("kervan." + k + ".z", v.z); y.set("kervan." + k + ".esya", v.esya); y.set("kervan." + k + ".enkaz", v.enkaz); y.set("kervan." + k + ".kaynak", v.kaynakKey); y.set("kervan." + k + ".hedef", v.hedefKey); y.set("kervan." + k + ".kx", v.kx); y.set("kervan." + k + ".kz", v.kz); y.set("kervan." + k + ".koygordu", v.koyGordu); });
            enkazlar.forEach((k, e) -> {
                String p = "enkaz." + k;
                y.set(p + ".kale", e.kaleId.toString()); y.set(p + ".dunya", e.dunya); y.set(p + ".x", e.x); y.set(p + ".z", e.z); y.set(p + ".esya", e.esya);
                y.set(p + ".son", e.sonYakinlik); y.set(p + ".acildi", e.sandikAcildi); y.set(p + ".yerlesti", e.sandikYerlesti);
                y.set(p + ".sahne", e.sahne); y.set(p + ".sahnehazir", e.sahneHazir);
                y.set(p + ".sx", e.sx); y.set(p + ".sy", e.sy); y.set(p + ".sz", e.sz);
            });
            File gecici = new File(plugin.getDataFolder(), "npc_dunya.yml.tmp");
            y.save(gecici);
            java.nio.file.Files.move(gecici.toPath(), dosya.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "npc_dunya.yml yazılamadı", ex);
        }
    }

    public void kapat() { kaydet(); }
}
