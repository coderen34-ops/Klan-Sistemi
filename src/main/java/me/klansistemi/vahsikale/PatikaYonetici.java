package me.klansistemi.vahsikale;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import me.klansistemi.KlanSistemi;

/**
 * Organik patika + kervan koprusu.
 *  - Patika: oyuncu/binek ve kervan gecislerinde sayac artar; GRASS -> COARSE_DIRT -> DIRT_PATH -> (serpistirilmis) GRAVEL.
 *    Her donusumden once ORIJINAL blok kaydedilir; kullanilmayan patika kademeli olarak eski haline doner (decay).
 *    Patika (DIRT_PATH/GRAVEL kademesi) uzerinde hafif Speed II bonusu verilir.
 *  - Kopru: kervan rotasindaki 3-8 bloklu su gecisi yeterince cok kullanilinca (config) meslek kalasindan kopru kurulur.
 *    claim / koy / vahsi kale icinde ASLA kurulmaz; orijinal bloklar kaydedilir, kullanilmazsa ya da admin komutuyla geri alinir.
 * Sayimlar bellekte tutulur, periyodik diske yazilir (atomik); veriYuklendi korumasi vardir.
 * Sinirlama: kervan soyut oldugu icin, kervan gecisi sadece YUKLU chunk'larda (ya da chunk yuklenince, bekleyen orneklerle) isler.
 */
public class PatikaYonetici implements Listener {

    private static final class Kayit {
        int asama; int sayac; long son; String orijinal; boolean yasak;
        UUID sonOyuncu; long sonOyuncuZaman;
    }

    private static final class Kopru {
        String anahtar; long son; final List<String> bloklar = new ArrayList<>(); // "x;y;z;blockdata"
        String dunya;
    }

    private final KlanSistemi plugin;
    private final Random rng = new Random();
    private final File dosya;
    private boolean yuklendi = false, kirli = false;

    private final Map<String, Kayit> kayitlar = new HashMap<>();
    private final Map<String, Integer> gecisSayaci = new HashMap<>();
    private final Map<String, Kopru> kopruler = new HashMap<>();
    private final Map<String, List<int[]>> bekleyen = new HashMap<>(); // "dunya;cx;cz" -> {x,z,xEkseni}
    private int bekleyenToplam = 0;

    public PatikaYonetici(KlanSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "patika.yml");
        yukle();
    }

    private boolean aktif() { return plugin.getConfig().getBoolean("npc.patika.aktif", true); }
    private int ayar(String yol, int v) { return plugin.getConfig().getInt("npc.patika." + yol, v); }

    private static String anahtar(World w, int x, int z) { return w.getName() + ";" + x + ";" + z; }

    // ------------------------------------------------------------------ OYUNCU GECISI
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent ev) {
        if (!aktif() || !yuklendi) return;
        Location f = ev.getFrom(), t = ev.getTo();
        if (t == null || (f.getBlockX() == t.getBlockX() && f.getBlockZ() == t.getBlockZ())) return; // sadece tam blok degisimi
        Player p = ev.getPlayer();
        if (p.isFlying() || p.isGliding() || p.isSwimming()) return;
        World w = t.getWorld();
        if (w == null || w.getEnvironment() != World.Environment.NORMAL) return;
        Block zemin = t.getBlock().getRelative(BlockFace.DOWN);
        Material m = zemin.getType();
        if (m != Material.GRASS_BLOCK && m != Material.DIRT && m != Material.COARSE_DIRT && m != Material.DIRT_PATH && m != Material.GRAVEL) return;
        Kayit k = sayacArtir(zemin, 1, p.getUniqueId());
        if (k != null && k.asama >= 2 && !k.yasak) hizBonusu(p);
    }

    private void hizBonusu(Player p) {
        PotionEffect cur = p.getPotionEffect(PotionEffectType.SPEED);
        if (cur == null || (cur.getAmplifier() == 1 && cur.getDuration() <= 40)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 50, 1, true, false, false)); // Speed II, kisa sureli (hafif)
        }
    }

    private boolean korumali(Block b) {
        World w = b.getWorld();
        int x = b.getX(), z = b.getZ();
        if (YasakAlan.koyaDegiyor(w, x, z, 2)) return true;
        if (YasakAlan.claimeDegiyor(w, x, z, 0)) return true;
        Location l = b.getLocation();
        for (VahsiKale k : plugin.vahsiKale().tumKaleler()) if (k.sinirUzakligi(l) < 8) return true;
        return false;
    }

    private Kayit sayacArtir(Block b, int artis, UUID oyuncu) {
        String key = anahtar(b.getWorld(), b.getX(), b.getZ());
        long simdi = System.currentTimeMillis();
        Kayit k = kayitlar.get(key);
        Material m = b.getType();
        if (k == null) {
            if (m != Material.GRASS_BLOCK && m != Material.DIRT) return null; // dogal kademe 0 sadece cimen/toprak
            if (kayitlar.size() >= 60000) return null;
            k = new Kayit();
            k.orijinal = b.getBlockData().getAsString();
            kayitlar.put(key, k);
        } else if (!beklenenTip(k, m)) { // oyuncu blogu degistirmis (insa vb.): kaydi birak, dokunma
            kayitlar.remove(key);
            kirli = true;
            return null;
        }
        if (k.yasak) return k;
        if (oyuncu != null) {
            if (oyuncu.equals(k.sonOyuncu) && simdi - k.sonOyuncuZaman < 20_000L) return k; // ayni yerde ileri-geri yurume sayilmaz
            k.sonOyuncu = oyuncu; k.sonOyuncuZaman = simdi;
        }
        k.sayac += artis;
        k.son = simdi;
        int hedef = k.sayac >= ayar("asama3", 250) ? 3 : k.sayac >= ayar("asama2", 80) ? 2 : k.sayac >= ayar("asama1", 25) ? 1 : 0;
        if (hedef > k.asama) ilerlet(b, k, hedef);
        return k;
    }

    private boolean beklenenTip(Kayit k, Material m) {
        return switch (k.asama) {
            case 0 -> m == Material.GRASS_BLOCK || m == Material.DIRT;
            case 1 -> m == Material.COARSE_DIRT;
            case 2 -> m == Material.DIRT_PATH;
            default -> m == Material.DIRT_PATH || m == Material.GRAVEL;
        };
    }

    private void ilerlet(Block b, Kayit k, int hedef) {
        if (k.asama == 0 && korumali(b)) { k.yasak = true; return; } // korumali bolgede hic degistirme
        if (hedef >= 1 && k.asama < 1) { b.setType(Material.COARSE_DIRT, false); k.asama = 1; kirli = true; }
        if (hedef >= 2 && k.asama < 2) {
            Block ust = b.getRelative(BlockFace.UP);
            if (!ust.getType().isAir() && ust.isPassable() && !ust.isLiquid()) ust.setType(Material.AIR, false); // ot/cicek: yol altinda yasayamaz
            if (!ust.getType().isAir() && !ust.isPassable()) { k.sayac = ayar("asama2", 80) - 1; return; } // ustu kati blok: patikaya cevirme
            b.setType(Material.DIRT_PATH, false);
            k.asama = 2; kirli = true;
        }
        if (hedef >= 3 && k.asama == 2) {
            if (rng.nextDouble() < 0.30) b.setType(Material.GRAVEL, false); // serpistirilmis
            k.asama = 3; kirli = true;
        }
    }

    // ------------------------------------------------------------------ KERVAN GECISI (soyut)
    /** Soyut kervan (x1,z1) -> (x2,z2) yolculugu yapti: yuklu chunk'larda hemen, digerlerinde chunk yuklenince islenir. */
    public void kervanGecisi(World w, double x1, double z1, double x2, double z2) {
        if (!aktif() || !yuklendi || w.getEnvironment() != World.Environment.NORMAL) return;
        double dx = x2 - x1, dz = z2 - z1, mesafe = Math.hypot(dx, dz);
        int adim = Math.max(1, (int) (mesafe / 12));
        boolean xe = Math.abs(dx) > Math.abs(dz);
        Set<String> gorulen = new HashSet<>();
        for (int i = 0; i <= adim; i++) {
            int x = (int) Math.floor(x1 + dx * i / adim), z = (int) Math.floor(z1 + dz * i / adim);
            if (w.isChunkLoaded(x >> 4, z >> 4)) ornekle(w, x, z, xe, gorulen);
            else {
                String ck = w.getName() + ";" + (x >> 4) + ";" + (z >> 4);
                List<int[]> l = bekleyen.computeIfAbsent(ck, a -> new ArrayList<>());
                if (l.size() < 40 && bekleyenToplam < 4000) { l.add(new int[]{x, z, xe ? 1 : 0}); bekleyenToplam++; }
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent ev) {
        if (bekleyen.isEmpty() || ev.isNewChunk()) return;
        String ck = ev.getWorld().getName() + ";" + ev.getChunk().getX() + ";" + ev.getChunk().getZ();
        List<int[]> l = bekleyen.remove(ck);
        if (l == null) return;
        bekleyenToplam -= l.size();
        Set<String> gorulen = new HashSet<>();
        for (int[] n : l) ornekle(ev.getWorld(), n[0], n[1], n[2] == 1, gorulen);
    }

    private void ornekle(World w, int x, int z, boolean xe, Set<String> gorulen) {
        try {
            int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
            Block b = w.getBlockAt(x, y, z);
            if (b.getType() == Material.WATER) { kopruAdayi(w, x, z, xe, gorulen); return; }
            int yg = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block zemin = w.getBlockAt(x, yg, z);
            Material m = zemin.getType();
            if (m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.COARSE_DIRT || m == Material.DIRT_PATH || m == Material.GRAVEL) {
                sayacArtir(zemin, 4, null);
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ KOPRU
    private void kopruAdayi(World w, int bx, int bz, boolean xe, Set<String> gorulen) {
        int ax = xe ? 1 : 0, az = xe ? 0 : 1;
        int hi = 0, lo = 0;
        while (hi < 9 && suYuzeyi(w, bx + ax * (hi + 1), bz + az * (hi + 1))) hi++;
        while (lo < 9 && suYuzeyi(w, bx - ax * (lo + 1), bz - az * (lo + 1))) lo++;
        int uzunluk = hi + lo + 1;
        if (uzunluk < 3 || uzunluk > 8) return;
        int ax1 = bx + ax * (hi + 1), az1 = bz + az * (hi + 1), ax2 = bx - ax * (lo + 1), az2 = bz - az * (lo + 1);
        if (!w.isChunkLoaded(ax1 >> 4, az1 >> 4) || !w.isChunkLoaded(ax2 >> 4, az2 >> 4)) return;
        int ya = w.getHighestBlockYAt(ax1, az1, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int yb = w.getHighestBlockYAt(ax2, az2, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Material ma = w.getBlockAt(ax1, ya, az1).getType(), mb = w.getBlockAt(ax2, yb, az2).getType();
        if (ya != yb || !kara(ma) || !kara(mb)) return;
        int sx = bx - ax * lo, sz = bz - az * lo;
        String key = w.getName() + ";" + (xe ? "X" : "Z") + ";" + sx + ";" + sz + ";" + uzunluk;
        if (!gorulen.add(key)) return; // bir yolculukta tek sayim
        Kopru mevcut = kopruler.get(key);
        if (mevcut != null) { mevcut.son = System.currentTimeMillis(); return; }
        int sayi = gecisSayaci.merge(key, 1, Integer::sum);
        kirli = true;
        if (sayi >= ayar("kopru-esik", 15)) {
            if (!kopruKur(w, key, sx, sz, ax, az, uzunluk, ya)) gecisSayaci.put(key, Integer.MIN_VALUE / 2); // uygun degil: bir daha deneme
        }
    }

    private boolean suYuzeyi(World w, int x, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
        return w.getBlockAt(x, w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE), z).getType() == Material.WATER;
    }

    private boolean kara(Material m) {
        return m.isSolid() && !Tag.LEAVES.isTagged(m) && !Tag.ICE.isTagged(m) && m != Material.WATER && m != Material.LAVA;
    }

    private boolean kopruKur(World w, String key, int sx, int sz, int ax, int az, int n, int deckY) {
        List<Block> hucreler = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Block b = w.getBlockAt(sx + ax * i, deckY, sz + az * i);
            if (korumali(b)) return false;
            Block u1 = b.getRelative(BlockFace.UP), u2 = u1.getRelative(BlockFace.UP);
            if ((!u1.getType().isAir() && !u1.isPassable()) || (!u2.getType().isAir() && !u2.isPassable())) return false; // ustu kapali
            if (b.getType() != Material.WATER && !b.getType().isAir()) return false;
            hucreler.add(b);
        }
        Kopru kp = new Kopru();
        kp.anahtar = key; kp.dunya = w.getName(); kp.son = System.currentTimeMillis();
        for (Block b : hucreler) kp.bloklar.add(b.getX() + ";" + b.getY() + ";" + b.getZ() + ";" + b.getBlockData().getAsString());
        kopruler.put(key, kp);
        kaydet(); // geri alma bilgisi YAPIDAN ONCE diske
        for (Block b : hucreler) b.setType(Material.OAK_PLANKS, false);
        plugin.getLogger().info("[Patika] Kervan koprusu kuruldu: " + key);
        return true;
    }

    private void kopruGeriAl(Kopru kp) {
        World w = Bukkit.getWorld(kp.dunya);
        if (w == null) return;
        for (String s : kp.bloklar) {
            String[] p = s.split(";", 4);
            Block b = w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            if (b.getType() == Material.OAK_PLANKS) b.setBlockData(Bukkit.createBlockData(p[3]), false); // oyuncu degistirdiyse dokunma
        }
    }

    // ------------------------------------------------------------------ DECAY / BAKIM
    /** Periyodik (10 dk): kullanilmayan patikalar bir kademe geri doner, kopruler kaldirilir, hafif kayitlar silinir. */
    public void bakim() {
        if (!aktif() || !yuklendi) return;
        long simdi = System.currentTimeMillis();
        long decay = ayar("decay-dakika", 240) * 60_000L;
        List<String> sil = new ArrayList<>();
        for (Map.Entry<String, Kayit> e : new ArrayList<>(kayitlar.entrySet())) {
            Kayit k = e.getValue();
            String[] p = e.getKey().split(";");
            World w = Bukkit.getWorld(p[0]);
            if (w == null) continue;
            int x = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
            if (k.asama == 0) { // hafif kayitlar
                if (k.sayac < 5 && simdi - k.son > 30 * 60_000L) sil.add(e.getKey());
                else if (simdi - k.son > decay) { k.sayac = Math.max(0, k.sayac - 5); k.son = simdi; if (k.sayac == 0) sil.add(e.getKey()); }
                continue;
            }
            if (simdi - k.son <= decay || !w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = w.getBlockAt(x, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES), z);
            if (!beklenenTip(k, b.getType())) { sil.add(e.getKey()); continue; } // degistirilmis: birak
            int yeni = k.asama - 1;
            if (yeni == 0) b.setBlockData(Bukkit.createBlockData(k.orijinal), false);
            else if (yeni == 1) b.setType(Material.COARSE_DIRT, false);
            else if (yeni == 2) b.setType(Material.DIRT_PATH, false);
            k.asama = yeni; k.son = simdi;
            k.sayac = yeni == 0 ? 0 : yeni == 1 ? ayar("asama1", 25) : ayar("asama2", 80);
            if (yeni == 0) sil.add(e.getKey());
            kirli = true;
        }
        for (String s : sil) kayitlar.remove(s);
        long kopruDecay = ayar("kopru-decay-dakika", 720) * 60_000L;
        for (Kopru kp : new ArrayList<>(kopruler.values())) {
            if (simdi - kp.son > kopruDecay) { kopruGeriAl(kp); kopruler.remove(kp.anahtar); gecisSayaci.remove(kp.anahtar); kirli = true; }
        }
        if (!sil.isEmpty()) kirli = true;
        if (kirli) kaydet();
    }

    /** Admin: tum patikalari ve koprulerı (degistirilmemis olanlari) eski haline dondurur. */
    public void hepsiniGeriAl(CommandSender s) {
        int p = 0, kb = 0;
        for (Map.Entry<String, Kayit> e : new ArrayList<>(kayitlar.entrySet())) {
            Kayit k = e.getValue();
            String[] a = e.getKey().split(";");
            World w = Bukkit.getWorld(a[0]);
            if (w == null || k.asama == 0) continue;
            int x = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue; // yuklu olmayan chunk'a dokunma; sonra tekrar calistirilabilir
            Block b = w.getBlockAt(x, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES), z);
            if (beklenenTip(k, b.getType())) { b.setBlockData(Bukkit.createBlockData(k.orijinal), false); p++; }
            kayitlar.remove(e.getKey());
        }
        for (Kopru kp : new ArrayList<>(kopruler.values())) { kopruGeriAl(kp); kopruler.remove(kp.anahtar); kb++; }
        gecisSayaci.clear();
        kirli = true; kaydet();
        s.sendMessage(ChatColor.GREEN + "Patika geri alindi: " + p + " blok, " + kb + " kopru (yuklu olmayan chunk'lar atlandi, tekrar calistirilabilir).");
    }

    public void bilgi(CommandSender s) {
        int[] st = new int[4];
        for (Kayit k : kayitlar.values()) st[k.asama]++;
        s.sendMessage(ChatColor.GOLD + "Patika: kademe0=" + st[0] + " kademe1=" + st[1] + " kademe2=" + st[2] + " kademe3=" + st[3]
                + " | kopru=" + kopruler.size() + " | bekleyen ornek=" + bekleyenToplam);
    }

    // ------------------------------------------------------------------ KALICILIK
    private void yukle() {
        try {
            if (dosya.exists()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
                for (String s : y.getStringList("kayit")) {
                    String[] p = s.split(";", 7);
                    Kayit k = new Kayit();
                    k.asama = Integer.parseInt(p[3]); k.sayac = Integer.parseInt(p[4]); k.son = Long.parseLong(p[5]); k.orijinal = p[6];
                    kayitlar.put(p[0] + ";" + p[1] + ";" + p[2], k);
                }
                for (String s : y.getStringList("gecis")) {
                    int i = s.lastIndexOf('|');
                    gecisSayaci.put(s.substring(0, i), Integer.parseInt(s.substring(i + 1)));
                }
                ConfigurationSection ks = y.getConfigurationSection("kopru");
                if (ks != null) for (String id : ks.getKeys(false)) {
                    Kopru kp = new Kopru();
                    kp.anahtar = ks.getString(id + ".anahtar"); kp.dunya = ks.getString(id + ".dunya"); kp.son = ks.getLong(id + ".son");
                    kp.bloklar.addAll(ks.getStringList(id + ".bloklar"));
                    kopruler.put(kp.anahtar, kp);
                }
            }
            yuklendi = true; // dosya yoksa ilk calisma; okuma hatasinda yuklendi=false kalir -> yazma KAPALI
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "patika.yml okunamadi - patika sistemi KAPALI (diskteki veri ezilmeyecek)", ex);
        }
    }

    public synchronized void kaydet() {
        if (!yuklendi) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            List<String> kl = new ArrayList<>();
            for (Map.Entry<String, Kayit> e : kayitlar.entrySet()) {
                Kayit k = e.getValue();
                if (k.asama == 0 && k.sayac < 5) continue;
                kl.add(e.getKey() + ";" + k.asama + ";" + k.sayac + ";" + k.son + ";" + k.orijinal);
            }
            y.set("kayit", kl);
            List<String> gl = new ArrayList<>();
            for (Map.Entry<String, Integer> e : gecisSayaci.entrySet()) gl.add(e.getKey() + "|" + e.getValue());
            y.set("gecis", gl);
            int i = 0;
            for (Kopru kp : kopruler.values()) {
                String p = "kopru." + (i++);
                y.set(p + ".anahtar", kp.anahtar); y.set(p + ".dunya", kp.dunya); y.set(p + ".son", kp.son); y.set(p + ".bloklar", kp.bloklar);
            }
            File gecici = new File(plugin.getDataFolder(), "patika.yml.tmp");
            y.save(gecici);
            java.nio.file.Files.move(gecici.toPath(), dosya.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            kirli = false;
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "patika.yml yazilamadi", ex);
        }
    }

    public void periyodikKaydet() { if (kirli) kaydet(); }
    public void kapat() { kaydet(); }
}
