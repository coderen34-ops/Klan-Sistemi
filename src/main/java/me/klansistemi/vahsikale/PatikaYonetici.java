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
 * Kervan soyut rotalari (dugum cifti) kalici tutulur: yuklu chunk'lara hemen, yuklenmemislere chunk ilk yuklendiginde (uretilirken bile) uygulanir.
 */
public class PatikaYonetici implements Listener {

    private static final class Kayit {
        int asama; int sayac; long son; String orijinal; String ust; boolean yasak;
        UUID sonOyuncu; long sonOyuncuZaman;
    }

    private static final class Kopru {
        String anahtar, dunya, rota; long son; final List<String> bloklar = new ArrayList<>(); // "x;y;z;blockdata"
    }

    private static final class Rota {
        String key, dunya; int x1, z1, x2, z2; int sayi;
    }

    private final KlanSistemi plugin;
    private final Random rng = new Random();
    private final File dosya;
    private boolean yuklendi = false, kirli = false;

    private final Map<String, Kayit> kayitlar = new HashMap<>();
    private final Map<String, Kopru> kopruler = new HashMap<>();
    private final Map<String, Rota> rotalar = new HashMap<>();        // kervan rotalari (dugum cifti)
    private final Map<String, Integer> uygulanan = new HashMap<>();   // "rota#dunya;cx;cz" -> o chunk'a uygulanmis gecis sayisi
    private final Set<String> basarisizKopru = new HashSet<>();      // uygun olmayan gecitler (runtime)

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
        if (p.isFlying() || p.isGliding() || p.isSwimming() || p.isInsideVehicle()) return;
        islem(p, p, t);
    }

    /** Binekte (at, katır, eşek, domuz...) giderken de patika sayılır ve hız bonusu bineğe verilir. */
    @EventHandler(ignoreCancelled = true)
    public void onBinekHareket(org.bukkit.event.vehicle.VehicleMoveEvent ev) {
        if (!aktif() || !yuklendi) return;
        if (!(ev.getVehicle() instanceof org.bukkit.entity.LivingEntity binek)) return; // tekne/vagon sayılmaz
        Location f = ev.getFrom(), t = ev.getTo();
        if (f.getBlockX() == t.getBlockX() && f.getBlockZ() == t.getBlockZ()) return;
        Player p = null;
        for (org.bukkit.entity.Entity e : binek.getPassengers()) if (e instanceof Player pl) { p = pl; break; }
        if (p == null) return;
        islem(p, binek, t);
    }

    private void islem(Player p, org.bukkit.entity.LivingEntity hizAlan, Location t) {
        World w = t.getWorld();
        if (w == null || w.getEnvironment() != World.Environment.NORMAL) return;
        Block zemin = t.getBlock().getRelative(BlockFace.DOWN);
        Material m = zemin.getType();
        if (m != Material.GRASS_BLOCK && m != Material.DIRT && m != Material.COARSE_DIRT && m != Material.DIRT_PATH && m != Material.GRAVEL) return;
        Kayit k = sayacArtir(zemin, 1, p.getUniqueId());
        if (k != null && k.asama >= 2 && !k.yasak) hizBonusu(hizAlan);
    }

    private void hizBonusu(org.bukkit.entity.LivingEntity e) {
        PotionEffect cur = e.getPotionEffect(PotionEffectType.SPEED);
        if (cur == null || (cur.getAmplifier() == 1 && cur.getDuration() <= 40)) {
            e.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 50, 1, true, false, false)); // Speed II, kisa sureli (hafif)
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
            if (!ust.getType().isAir() && ust.isPassable() && !ust.isLiquid()) { k.ust = ust.getBlockData().getAsString(); ust.setType(Material.AIR, false); } // ot/cicek kaydedilir, patika eski haline donunce geri gelir
            if (!ust.getType().isAir() && !ust.isPassable()) { k.sayac = ayar("asama2", 80) - 1; return; } // ustu kati blok: patikaya cevirme
            b.setType(Material.DIRT_PATH, false);
            k.asama = 2; kirli = true;
        }
        if (hedef >= 3 && k.asama == 2) {
            if (rng.nextDouble() < 0.30) b.setType(Material.GRAVEL, false); // serpistirilmis
            k.asama = 3; kirli = true;
        }
    }

    // ------------------------------------------------------------------ KERVAN ROTALARI (soyut)
    /** Soyut kervan bir dugumden digerine ulasti: rotanin gecis sayisi artar; YUKLU chunk'lara hemen, digerlerine chunk yuklenince uygulanir. */
    public void rotaEkle(World w, String key, int x1, int z1, int x2, int z2) {
        if (!aktif() || !yuklendi || w.getEnvironment() != World.Environment.NORMAL) return;
        Rota r = rotalar.get(key);
        if (r == null) {
            r = new Rota(); r.key = key; r.dunya = w.getName(); r.x1 = x1; r.z1 = z1; r.x2 = x2; r.z2 = z2;
            rotalar.put(key, r);
        }
        r.sayi++;
        kirli = true;
        long simdi = System.currentTimeMillis();
        for (Kopru kp : kopruler.values()) if (key.equals(kp.rota)) kp.son = simdi; // rota kullanildikca kopru omru uzar
        // yuklu chunk'lara hemen uygula
        Set<Long> chunklar = rotaChunklari(r);
        for (long c : chunklar) {
            int cx = (int) (c >> 32), cz = (int) c;
            if (w.isChunkLoaded(cx, cz)) rotaChunkUygula(w, r, cx, cz);
        }
        kopruTara(w, r);
    }

    private Set<Long> rotaChunklari(Rota r) {
        Set<Long> s = new HashSet<>();
        double dx = r.x2 - r.x1, dz = r.z2 - r.z1, len = Math.max(1, Math.hypot(dx, dz));
        int adim = (int) (len / 8) + 1;
        for (int i = 0; i <= adim; i++) {
            int x = (int) Math.floor(r.x1 + dx * i / adim), z = (int) Math.floor(r.z1 + dz * i / adim);
            s.add((((long) (x >> 4)) << 32) | ((z >> 4) & 0xffffffffL));
        }
        return s;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent ev) {
        if (!aktif() || !yuklendi || rotalar.isEmpty()) return;
        final World w = ev.getWorld();
        final int cx = ev.getChunk().getX(), cz = ev.getChunk().getZ();
        int bx0 = cx << 4, bx1 = bx0 + 15, bz0 = cz << 4, bz1 = bz0 + 15;
        List<Rota> ilgili = new ArrayList<>();
        for (Rota r : rotalar.values()) {
            if (!r.dunya.equals(w.getName())) continue;
            if (Math.max(r.x1, r.x2) < bx0 - 2 || Math.min(r.x1, r.x2) > bx1 + 2 || Math.max(r.z1, r.z2) < bz0 - 2 || Math.min(r.z1, r.z2) > bz1 + 2) continue;
            if (rotaChunklari(r).contains((((long) cx) << 32) | (cz & 0xffffffffL))) ilgili.add(r);
        }
        if (ilgili.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> { // bir tik sonra: yeni uretilen chunk'ta da guvenli
            if (!w.isChunkLoaded(cx, cz)) return;
            for (Rota r : ilgili) { rotaChunkUygula(w, r, cx, cz); kopruTara(w, r); }
        });
    }

    private void rotaChunkUygula(World w, Rota r, int cx, int cz) {
        String k2 = r.key + "#" + w.getName() + ";" + cx + ";" + cz;
        int delta = r.sayi - uygulanan.getOrDefault(k2, 0);
        if (delta <= 0) return;
        uygulanan.put(k2, r.sayi);
        kirli = true;
        double dx = r.x2 - r.x1, dz = r.z2 - r.z1, len = Math.max(1, Math.hypot(dx, dz));
        boolean xe = Math.abs(dx) >= Math.abs(dz);
        int isx = dx >= 0 ? 1 : -1, isz = dz >= 0 ? 1 : -1;
        int adim = (int) (len / 0.7) + 1;
        int sonX = Integer.MIN_VALUE, sonZ = Integer.MIN_VALUE;
        int oncekiKaraY = Integer.MIN_VALUE, oncekiKaraX = 0, oncekiKaraZ = 0;
        for (int i = 0; i <= adim; i++) {
            int x = (int) Math.floor(r.x1 + dx * i / adim), z = (int) Math.floor(r.z1 + dz * i / adim);
            if (x == sonX && z == sonZ) continue;
            sonX = x; sonZ = z;
            if ((x >> 4) != cx || (z >> 4) != cz) continue;
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block zemin = w.getBlockAt(x, y, z);
            Material m = zemin.getType();
            if (m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.COARSE_DIRT || m == Material.DIRT_PATH || m == Material.GRAVEL) {
                sayacArtir(zemin, 4 * delta, null);
            }
        }
    }

    /** Rota boyunca yuklu bolgelerde su/ucurum gecidi arar; esik asildiysa kopru kurmayi dener. */
    private void kopruTara(World w, Rota r) {
        if (r.sayi < ayar("kopru-esik", 15)) return;
        double dx = r.x2 - r.x1, dz = r.z2 - r.z1, len = Math.max(1, Math.hypot(dx, dz));
        boolean xe = Math.abs(dx) >= Math.abs(dz);
        int isx = dx >= 0 ? 1 : -1, isz = dz >= 0 ? 1 : -1;
        int adim = (int) (len / 0.7) + 1;
        int sonX = Integer.MIN_VALUE, sonZ = Integer.MIN_VALUE;
        int oy = Integer.MIN_VALUE, ox = 0, oz = 0;
        for (int i = 0; i <= adim; i++) {
            int x = (int) Math.floor(r.x1 + dx * i / adim), z = (int) Math.floor(r.z1 + dz * i / adim);
            if (x == sonX && z == sonZ) continue;
            sonX = x; sonZ = z;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) { oy = Integer.MIN_VALUE; continue; }
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Material m = w.getBlockAt(x, y, z).getType();
            boolean su = m == Material.WATER;
            boolean uc = !su && oy != Integer.MIN_VALUE && y < oy - 4;
            if (su || uc) {
                if (oy != Integer.MIN_VALUE) kopruDene(w, r, ox, oz, oy, xe, xe ? isx : isz);
                oy = Integer.MIN_VALUE; // gecit denendi; bir sonraki kara hucresinden devam
            } else if (kara(m)) { oy = y; ox = x; oz = z; }
        }
    }

    // ------------------------------------------------------------------ KOPRU (su + ucurum, 3-32 blok, saglam)
    private static final Material DECK = Material.SPRUCE_PLANKS, RAIL = Material.SPRUCE_FENCE, PILLAR = Material.STRIPPED_SPRUCE_LOG;

    private boolean kara(Material m) {
        return m.isSolid() && !Tag.LEAVES.isTagged(m) && !Tag.ICE.isTagged(m) && m != Material.WATER && m != Material.LAVA;
    }

    private void kopruDene(World w, Rota r, int lx, int lz, int ly, boolean xe, int yon) {
        int ax = xe ? yon : 0, az = xe ? 0 : yon;
        int n = 0;
        boolean bitti = false;
        for (int i = 1; i <= 33; i++) {
            int x = lx + ax * i, z = lz + az * i;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) return; // henuz yuklu degil: sonra tekrar denenir
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Material m = w.getBlockAt(x, y, z).getType();
            if (m == Material.LAVA) return;
            boolean su = m == Material.WATER;
            boolean uc = !su && y < ly - 4;
            if (su || uc) {
                if (su && y > ly) return; // su kiyidan yuksek: kopru kurma
                n = i; continue;
            }
            // kara bulundu: yukseklik uyumu
            if (!kara(m) || Math.abs(y - ly) > 1) return;
            bitti = true;
            break;
        }
        if (!bitti || n < 3 || n > 32) return;
        String key = w.getName() + ";" + (xe ? "X" : "Z") + ";" + lx + ";" + lz + ";" + yon + ";" + n;
        if (kopruler.containsKey(key) || !basarisizKopru.add(key)) return; // zaten var / daha once uygun degildi (bu calisma boyunca)
        if (kopruKur(w, r, key, lx, lz, ax, az, n, ly)) basarisizKopru.remove(key);
    }

    private boolean kopruKur(World w, Rota r, String key, int lx, int lz, int ax, int az, int n, int deckY) {
        int px = az != 0 ? 1 : 0, pz = ax != 0 ? 1 : 0; // dik eksen
        java.util.LinkedHashMap<String, Block> degisecek = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<Block, Material> yeni = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= n; i++) {
            int cx = lx + ax * i, cz = lz + az * i;
            Block merkez = w.getBlockAt(cx, deckY, cz);
            if (korumali(merkez)) return false;
            Material mm = merkez.getType();
            if (mm != Material.WATER && !mm.isAir()) return false; // merkez serit serbest olmali
            for (int o = 1; o <= 2; o++) { // ust: 2 blok bosluk
                Block u = merkez.getRelative(BlockFace.UP, o);
                if (!u.getType().isAir() && !u.isPassable()) return false;
            }
            for (int d = -1; d <= 1; d++) {
                Block b = w.getBlockAt(cx + px * d, deckY, cz + pz * d);
                Material m = b.getType();
                if (d != 0 && !(m == Material.WATER || m.isAir())) continue; // yan serit: dolu ise atla (arazi)
                if (d != 0 && korumali(b)) continue;
                yeni.put(b, DECK);
                if (Math.abs(d) == 1) { // korkuluk
                    Block rl = b.getRelative(BlockFace.UP);
                    if (rl.getType().isAir() || rl.isPassable()) {
                        yeni.put(rl, RAIL);
                        if (i % 8 == 4) { Block fan = rl.getRelative(BlockFace.UP); if (fan.getType().isAir()) yeni.put(fan, Material.LANTERN); }
                    }
                }
            }
            // ayaklar: her 6 blokta bir, 3 sutun, saglam zemine kadar (en fazla 60)
            if (i % 6 == 3) {
                for (int d = -1; d <= 1; d++) {
                    int bx = cx + px * d, bz = cz + pz * d;
                    for (int dy = 1; dy <= 60; dy++) {
                        Block b = w.getBlockAt(bx, deckY - dy, bz);
                        Material m = b.getType();
                        if (!(m == Material.WATER || m.isAir() || m == Material.CAVE_AIR)) break; // saglam zemine ulasti
                        if (b.getY() <= w.getMinHeight()) break;
                        yeni.put(b, PILLAR);
                    }
                }
            }
        }
        if (yeni.size() > 3000) return false;
        Kopru kp = new Kopru();
        kp.anahtar = key; kp.dunya = w.getName(); kp.rota = r.key; kp.son = System.currentTimeMillis();
        for (Block b : yeni.keySet()) kp.bloklar.add(b.getX() + ";" + b.getY() + ";" + b.getZ() + ";" + b.getBlockData().getAsString());
        kopruler.put(key, kp);
        kirli = true;
        kaydet(); // geri alma bilgisi YAPIDAN ONCE diske
        for (Map.Entry<Block, Material> e : yeni.entrySet()) {
            Block b = e.getKey();
            if (e.getValue() == PILLAR) b.setBlockData(Bukkit.createBlockData("minecraft:stripped_spruce_log[axis=y]"), false);
            else if (e.getValue() == RAIL) b.setType(RAIL, false);
            else if (e.getValue() == Material.LANTERN) b.setBlockData(Bukkit.createBlockData("minecraft:lantern[hanging=false]"), false);
            else b.setType(DECK, false);
        }
        plugin.getLogger().info("[Patika] Kervan koprusu kuruldu (" + n + " blok, " + yeni.size() + " blok): " + key);
        return true;
    }

    private void kopruGeriAl(Kopru kp) {
        World w = Bukkit.getWorld(kp.dunya);
        if (w == null) return;
        for (String s : kp.bloklar) {
            String[] p = s.split(";", 4);
            Block b = w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            Material m = b.getType();
            if (m == DECK || m == RAIL || m == PILLAR || m == Material.LANTERN) b.setBlockData(Bukkit.createBlockData(p[3]), false); // oyuncu degistirdiyse dokunma
        }
    }

    // ------------------------------------------------------------------ DECAY / BAKIM
    /** Periyodik (10 dk): kullanilmayan patikalar bir kademe geri doner (cicekler geri gelir), kopruler kaldirilir. */
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
            if (k.asama == 0) {
                if (k.sayac < 5 && simdi - k.son > 30 * 60_000L) sil.add(e.getKey());
                else if (simdi - k.son > decay) { k.sayac = Math.max(0, k.sayac - 5); k.son = simdi; if (k.sayac == 0) sil.add(e.getKey()); }
                continue;
            }
            if (simdi - k.son <= decay || !w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = w.getBlockAt(x, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES), z);
            if (!beklenenTip(k, b.getType())) { sil.add(e.getKey()); continue; } // degistirilmis: birak
            int yeni = k.asama - 1;
            if (yeni == 0) {
                b.setBlockData(Bukkit.createBlockData(k.orijinal), false);
                geriCicek(b, k);
            } else if (yeni == 1) b.setType(Material.COARSE_DIRT, false);
            else if (yeni == 2) b.setType(Material.DIRT_PATH, false);
            k.asama = yeni; k.son = simdi;
            k.sayac = yeni == 0 ? 0 : yeni == 1 ? ayar("asama1", 25) : ayar("asama2", 80);
            if (yeni == 0) sil.add(e.getKey());
            kirli = true;
        }
        for (String s : sil) kayitlar.remove(s);
        long kopruDecay = ayar("kopru-decay-dakika", 720) * 60_000L;
        for (Kopru kp : new ArrayList<>(kopruler.values())) {
            if (simdi - kp.son > kopruDecay) { kopruGeriAl(kp); kopruler.remove(kp.anahtar); basarisizKopru.remove(kp.anahtar); kirli = true; }
        }
        if (!sil.isEmpty()) kirli = true;
        if (kirli) kaydet();
    }

    /** Patika eski haline donunce kayitli ot/cicek geri gelir (ustu bossa). */
    private void geriCicek(Block zemin, Kayit k) {
        if (k.ust == null) return;
        Block ust = zemin.getRelative(BlockFace.UP);
        if (ust.getType().isAir()) ust.setBlockData(Bukkit.createBlockData(k.ust), false);
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
            if (beklenenTip(k, b.getType())) { b.setBlockData(Bukkit.createBlockData(k.orijinal), false); geriCicek(b, k); p++; }
            kayitlar.remove(e.getKey());
        }
        for (Kopru kp : new ArrayList<>(kopruler.values())) { kopruGeriAl(kp); kopruler.remove(kp.anahtar); kb++; }
        uygulanan.clear();
        basarisizKopru.clear();
        kirli = true; kaydet();
        s.sendMessage(ChatColor.GREEN + "Patika geri alindi: " + p + " blok, " + kb + " kopru (yuklu olmayan chunk'lar atlandi, tekrar calistirilabilir). Rota sayaclari korunur; sifirlamak icin rotalar da silinir.");
        rotalar.clear(); kaydet();
    }

    /** Admin icin: kervan rotalarinin ve en gelismis patika noktalarinin koordinatlari. */
    public void liste(Player p) {
        p.sendMessage(ChatColor.GOLD + "Kervan rotalari (" + rotalar.size() + "):");
        int n = 0;
        for (Rota r : rotalar.values()) {
            if (++n > 15) { p.sendMessage(ChatColor.GRAY + "... (" + (rotalar.size() - 15) + " rota daha)"); break; }
            p.sendMessage(ChatColor.YELLOW + " " + r.dunya + ": " + r.x1 + "," + r.z1 + " -> " + r.x2 + "," + r.z2
                    + ChatColor.GRAY + "  /tp " + r.x1 + " ~ " + r.z1);
        }
        p.sendMessage(ChatColor.GOLD + "En gelismis patika noktalari (kademe 3):");
        int m = 0;
        for (Map.Entry<String, Kayit> e : kayitlar.entrySet()) {
            if (e.getValue().asama < 3) continue;
            if (++m > 10) break;
            String[] k = e.getKey().split(";");
            p.sendMessage(ChatColor.YELLOW + " " + k[0] + ": " + k[1] + ", " + k[2]);
        }
        if (m == 0) p.sendMessage(ChatColor.GRAY + " (henuz kademe 3 patika yok)");
    }

    public void bilgi(CommandSender s) {
        int[] st = new int[4];
        for (Kayit k : kayitlar.values()) st[k.asama]++;
        s.sendMessage(ChatColor.GOLD + "Patika: kademe0=" + st[0] + " kademe1=" + st[1] + " kademe2=" + st[2] + " kademe3=" + st[3]
                + " | kopru=" + kopruler.size() + " | kervan rotasi=" + rotalar.size());
    }

    // ------------------------------------------------------------------ KALICILIK
    private void yukle() {
        try {
            if (dosya.exists()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
                for (String s : y.getStringList("kayit")) {
                    String[] p = s.split(";", 8);
                    Kayit k = new Kayit();
                    k.asama = Integer.parseInt(p[3]); k.sayac = Integer.parseInt(p[4]); k.son = Long.parseLong(p[5]); k.orijinal = p[6];
                    k.ust = p.length > 7 && !p[7].isEmpty() ? p[7] : null;
                    kayitlar.put(p[0] + ";" + p[1] + ";" + p[2], k);
                }
                for (String s : y.getStringList("rota")) {
                    String[] p = s.split("\\|");
                    Rota r = new Rota();
                    r.key = p[0]; r.dunya = p[1]; r.x1 = Integer.parseInt(p[2]); r.z1 = Integer.parseInt(p[3]); r.x2 = Integer.parseInt(p[4]); r.z2 = Integer.parseInt(p[5]); r.sayi = Integer.parseInt(p[6]);
                    rotalar.put(r.key, r);
                }
                for (String s : y.getStringList("uygulanan")) {
                    int i = s.lastIndexOf('=');
                    uygulanan.put(s.substring(0, i), Integer.parseInt(s.substring(i + 1)));
                }
                ConfigurationSection ks = y.getConfigurationSection("kopru");
                if (ks != null) for (String id : ks.getKeys(false)) {
                    Kopru kp = new Kopru();
                    kp.anahtar = ks.getString(id + ".anahtar"); kp.dunya = ks.getString(id + ".dunya"); kp.rota = ks.getString(id + ".rota"); kp.son = ks.getLong(id + ".son");
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
                kl.add(e.getKey() + ";" + k.asama + ";" + k.sayac + ";" + k.son + ";" + k.orijinal + ";" + (k.ust == null ? "" : k.ust));
            }
            y.set("kayit", kl);
            List<String> rl = new ArrayList<>();
            for (Rota r : rotalar.values()) rl.add(r.key + "|" + r.dunya + "|" + r.x1 + "|" + r.z1 + "|" + r.x2 + "|" + r.z2 + "|" + r.sayi);
            y.set("rota", rl);
            List<String> ul = new ArrayList<>();
            for (Map.Entry<String, Integer> e : uygulanan.entrySet()) ul.add(e.getKey() + "=" + e.getValue());
            y.set("uygulanan", ul);
            int i = 0;
            for (Kopru kp : kopruler.values()) {
                String p = "kopru." + (i++);
                y.set(p + ".anahtar", kp.anahtar); y.set(p + ".dunya", kp.dunya); y.set(p + ".rota", kp.rota); y.set(p + ".son", kp.son); y.set(p + ".bloklar", kp.bloklar);
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
