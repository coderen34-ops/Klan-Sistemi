package me.klansistemi.savas;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

import me.klansistemi.KlanSistemi;

/**
 * Klan savaşı için haritada rastgele, uzak ve düz bir savaş alanı (config: savas.rastgele).
 *
 * Arama (sunucuyu dondurmadan):
 *  1) Ön eleme, dünya ÜRETİLMEDEN: vanilla biyom hesabıyla aday merkezler taranır (tick başına sınırlı);
 *     alanın 5x5 noktasının hepsi düz bir biyomdaysa (ova, savan, çöl...) aday geçer.
 *  2) Merkez kontrolü: sadece ortadaki 3x3 chunk asenkron yüklenip düzlüğe bakılır.
 *  3) Tam kontrol: merkez uygunsa alanın tüm chunk'ları yüklenip düzlük (ağaç/bitki hariç, uç %5'ler hariç),
 *     su/buz oranı, claim/güvenli bölge ve diğer savaşlarla çakışma kontrol edilir.
 *
 * Alan havuzu: sunucu boştayken arka planda bulunan alanlar savas-alanlari.yml'de saklanır. Savaş kabul edilince
 * havuzdan bir alan (zaten üretilmiş, birkaç saniyede) yeniden doğrulanarak kullanılır; havuz boşsa canlı aranır.
 */
public class RastgeleArena {

    /** Bulunan alan: blok sınırları ve iki takımın doğma noktası. */
    private record Alan(World w, int x1, int z1, int x2, int z2, int enAz, int enCok, Location s1, Location s2) {}

    private final KlanSistemi plugin;
    private final File havuzDosyasi;
    private final List<int[]> havuz = new ArrayList<>(); // {merkezX, merkezZ}
    private boolean arkaPlanAramasi = false;

    public RastgeleArena(KlanSistemi plugin) {
        this.plugin = plugin;
        this.havuzDosyasi = new File(plugin.getDataFolder(), "savas-alanlari.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(havuzDosyasi);
        for (String s : y.getStringList("alanlar")) {
            String[] p = s.split(",");
            try { havuz.add(new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())}); } catch (RuntimeException ignored) {}
        }
        // Arka plan havuz doldurma: savaş yokken ve sunucu rahatken, aralıkla tek arama
        long aralik = Math.max(1, (long) c("havuz-aralik-dakika", 10)) * 60 * 20;
        Bukkit.getScheduler().runTaskTimer(plugin, this::havuzDoldur, 20L * 60, aralik);
    }

    private double c(String yol, double v) {
        double d = plugin.getConfig().getDouble("savas.rastgele." + yol, v);
        return Double.isFinite(d) && d >= 0 ? d : v;
    }
    private int boyut() { return Math.max(30, (int) c("boyut", 100)); }
    private int minUzaklik() { return (int) c("min-uzaklik", 2000); }
    private int maxUzaklik() { return Math.max(minUzaklik() + 100, (int) c("max-uzaklik", 8000)); }
    private int maxFark() { return (int) c("max-yukseklik-farki", 8); }
    private double maxSivi() { return c("max-sivi-orani", 0.10); }
    private int deneme() { return Math.max(1, (int) c("deneme", 6)); }
    private int havuzBoyutu() { return (int) c("havuz-boyutu", 5); }

    private World dunya() {
        String ad = plugin.getConfig().getString("savas.rastgele.dunya", "");
        World w = ad.isEmpty() ? null : Bukkit.getWorld(ad);
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    /** Rastgele mod açık mı? (savas.arena-modu: rastgele | sabit) */
    public boolean aktif() {
        return !"sabit".equalsIgnoreCase(plugin.getConfig().getString("savas.arena-modu", "rastgele"));
    }

    // ------------------------------------------------------------------ SAVAŞ İÇİN ALAN
    /**
     * Savaş için alan bulur: önce havuzdan (yeniden doğrulanarak), yoksa canlı arama.
     * Bulununca arena doldurulur ve tamam(true); bulunamazsa tamam(false). Savaş arada biterse durur.
     */
    public void ara(Savas s, Consumer<Boolean> tamam) {
        BooleanSupplier bitti = () -> s.arena.kullanan != s;
        List<int[]> adaylar = new ArrayList<>(havuz);
        Collections.shuffle(adaylar);
        havuzdanDene(s, adaylar, bitti, tamam);
    }

    private void havuzdanDene(Savas s, List<int[]> adaylar, BooleanSupplier bitti, Consumer<Boolean> tamam) {
        if (bitti.getAsBoolean()) return;
        if (adaylar.isEmpty()) {
            new Arama(bitti, a -> {
                if (a == null) { tamam.accept(false); return; }
                havuzaEkle(a);
                uygula(s, a);
                tamam.accept(true);
            }).basla();
            return;
        }
        int[] m = adaylar.remove(0);
        tamKontrol(dunya(), m[0], m[1], s, bitti, a -> {
            if (a == null) {
                havuzdanCikar(m); // Artık uygun değil (claim, yapı, başka savaş...)
                havuzdanDene(s, adaylar, bitti, tamam);
            } else {
                uygula(s, a);
                tamam.accept(true);
            }
        });
    }

    private void uygula(Savas s, Alan a) {
        // Kutunun yüksekliği: zeminin biraz altından gökyüzüne (zıplama, rüzgâr yükü, topuz için pay)
        s.arena.pos1 = new Location(a.w(), a.x1(), Math.max(a.w().getMinHeight(), a.enAz() - 12), a.z1());
        s.arena.pos2 = new Location(a.w(), a.x2(), Math.min(a.w().getMaxHeight() - 1, a.enCok() + 80), a.z2());
        s.arena.spawn1 = a.s1();
        s.arena.spawn2 = a.s2();
        a.w().addPluginChunkTicket(a.s1().getBlockX() >> 4, a.s1().getBlockZ() >> 4, plugin);
        a.w().addPluginChunkTicket(a.s2().getBlockX() >> 4, a.s2().getBlockZ() >> 4, plugin);
        plugin.getLogger().info("[Savaş] Savaş alanı: " + a.w().getName() + " " + (a.x1() + a.x2()) / 2 + ", " + (a.z1() + a.z2()) / 2);
    }

    // ------------------------------------------------------------------ HAVUZ
    private void havuzDoldur() {
        if (!aktif() || arkaPlanAramasi || havuz.size() >= havuzBoyutu() || !plugin.savas().aktifSavaslar().isEmpty()) return;
        if (Bukkit.getServer().getTPS()[0] < 18.5) return; // Sunucu zorlanıyorsa beklenir
        arkaPlanAramasi = true;
        new Arama(() -> !plugin.isEnabled(), a -> {
            arkaPlanAramasi = false;
            if (a != null) havuzaEkle(a);
        }).basla();
    }

    private void havuzaEkle(Alan a) {
        int cx = (a.x1() + a.x2()) / 2, cz = (a.z1() + a.z2()) / 2;
        for (int[] m : havuz) if (m[0] == cx && m[1] == cz) return;
        havuz.add(new int[]{cx, cz});
        havuzKaydet();
    }

    private void havuzdanCikar(int[] m) {
        havuz.removeIf(x -> x[0] == m[0] && x[1] == m[1]);
        havuzKaydet();
    }

    private void havuzKaydet() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> l = new ArrayList<>();
        for (int[] m : havuz) l.add(m[0] + "," + m[1]);
        y.set("alanlar", l);
        try { y.save(havuzDosyasi); } catch (IOException e) { plugin.getLogger().warning("savas-alanlari.yml kaydedilemedi: " + e.getMessage()); }
    }

    /** /klan admin savasalani <liste|bul|temizle> */
    public void komut(CommandSender s, String[] args) {
        String islem = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "liste";
        switch (islem) {
            case "bul" -> {
                if (arkaPlanAramasi) { plugin.mesaj().gonder(s, "savasalani-zaten", "&eZaten bir arama sürüyor."); return; }
                arkaPlanAramasi = true;
                plugin.mesaj().gonder(s, "savasalani-araniyor", "&7Savaş alanı aranıyor (sunucuyu yormadan, 1-2 dakika sürebilir)...");
                long bas = System.currentTimeMillis();
                new Arama(() -> !plugin.isEnabled(), a -> {
                    arkaPlanAramasi = false;
                    if (a == null) { plugin.mesaj().gonder(s, "savasalani-yok", "&cUygun alan bulunamadı. Tekrar deneyin ya da config'te ölçütleri gevşetin."); return; }
                    havuzaEkle(a);
                    plugin.mesaj().gonder(s, "savasalani-bulundu", "&aAlan bulundu ve havuza eklendi: &f{x}, {z} &7({sn} sn, havuzda {sayi})",
                            "x", (a.x1() + a.x2()) / 2, "z", (a.z1() + a.z2()) / 2, "sn", (System.currentTimeMillis() - bas) / 1000, "sayi", havuz.size());
                }).basla();
            }
            case "temizle" -> {
                havuz.clear();
                havuzKaydet();
                plugin.mesaj().gonder(s, "savasalani-temizlendi", "&eSavaş alanı havuzu temizlendi.");
            }
            default -> {
                List<String> l = new ArrayList<>();
                for (int[] m : havuz) l.add(m[0] + ", " + m[1]);
                plugin.mesaj().gonder(s, "savasalani-liste", "&6Hazır savaş alanları ({sayi}/{max}): &f{liste}", "sayi", havuz.size(), "max", havuzBoyutu(),
                        "liste", l.isEmpty() ? "-" : String.join(" | ", l));
            }
        }
    }

    // ------------------------------------------------------------------ ARAMA
    /** Canlı arama: biyom ön elemesi -> merkez kontrolü -> tam kontrol. Sonuç ana thread'de verilir (null = bulunamadı). */
    private class Arama {
        final BooleanSupplier bitti;
        final Consumer<Alan> sonuc;
        final World w = dunya();
        final Set<String> uygun = new HashSet<>();
        int taranan = 0, denenen = 0;

        Arama(BooleanSupplier bitti, Consumer<Alan> sonuc) {
            this.bitti = bitti;
            this.sonuc = sonuc;
            for (String b : plugin.getConfig().getStringList("savas.rastgele.uygun-biyomlar")) uygun.add(b.toLowerCase(Locale.ROOT));
            if (uygun.isEmpty()) uygun.addAll(List.of("plains", "sunflower_plains", "savanna", "desert", "snowy_plains"));
        }

        void basla() { tara(); }

        /** Bu tick'te en fazla 10 aday biyomdan elenir (~15 ms); geçen olursa merkezi denenir, yoksa sonraki tick devam. */
        void tara() {
            if (bitti.getAsBoolean()) return;
            int maxAday = (int) c("aday-sayisi", 400);
            for (int i = 0; i < 10 && taranan < maxAday; i++, taranan++) {
                int[] m = merkez();
                if (m != null && biyomUygun(m[0], m[1])) {
                    if (++denenen > deneme()) { sonuc.accept(null); return; }
                    taranan++;
                    merkezKontrol(m[0], m[1]);
                    return;
                }
            }
            if (taranan >= maxAday) { sonuc.accept(null); return; }
            Bukkit.getScheduler().runTaskLater(plugin, this::tara, 1L);
        }

        int[] merkez() {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            Location spawn = w.getSpawnLocation();
            double aci = r.nextDouble(Math.PI * 2);
            int uzaklik = r.nextInt(minUzaklik(), maxUzaklik() + 1);
            int cx = spawn.getBlockX() + (int) (Math.cos(aci) * uzaklik), cz = spawn.getBlockZ() + (int) (Math.sin(aci) * uzaklik);
            int yari = boyut() / 2;
            if (!w.getWorldBorder().isInside(new Location(w, cx - yari, 64, cz - yari)) || !w.getWorldBorder().isInside(new Location(w, cx + yari, 64, cz + yari))) return null;
            return new int[]{cx, cz};
        }

        boolean biyomUygun(int cx, int cz) {
            org.bukkit.generator.BiomeProvider bp = w.vanillaBiomeProvider();
            int yari = boyut() / 2, y = w.getSeaLevel() + 4;
            for (int i = -2; i <= 2; i++) {
                for (int j = -2; j <= 2; j++) {
                    if (!uygun.contains(bp.getBiome(w, cx + i * yari / 2, y, cz + j * yari / 2).getKey().getKey())) return false;
                }
            }
            return true;
        }

        /** Sadece ortadaki 3x3 chunk: düz değilse tüm alan hiç üretilmez. */
        void merkezKontrol(int cx, int cz) {
            List<CompletableFuture<org.bukkit.Chunk>> yuklemeler = new ArrayList<>();
            for (int x = (cx >> 4) - 1; x <= (cx >> 4) + 1; x++) {
                for (int z = (cz >> 4) - 1; z <= (cz >> 4) + 1; z++) yuklemeler.add(w.getChunkAtAsync(x, z, true));
            }
            CompletableFuture.allOf(yuklemeler.toArray(new CompletableFuture[0])).whenComplete((v, hata) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (bitti.getAsBoolean()) return;
                        // Sadece YÜKLENEN 3x3 chunk'ın içi taranır: dışına taşan bir okuma, chunk'ı ana thread'de
                        // senkron üretip sunucuyu dondurur (ölçümde 0,3-2,3 sn)
                        int bx = ((cx >> 4) - 1) << 4, bz = ((cz >> 4) - 1) << 4;
                        if (hata != null || duzlukHatasi(w, bx, bz, bx + 47, bz + 47) != null) { tara(); return; }
                        tamKontrol(w, cx, cz, null, bitti, a -> { if (a != null) sonuc.accept(a); else tara(); });
                    }));
        }
    }

    /** Tüm alanın chunk'larını yükleyip ayrıntılı değerlendirir; sonuç ana thread'de (null = uygun değil). */
    private void tamKontrol(World w, int cx, int cz, Savas haric, BooleanSupplier bitti, Consumer<Alan> sonuc) {
        int yari = boyut() / 2;
        int x1 = cx - yari, z1 = cz - yari, x2 = cx + yari - 1, z2 = cz + yari - 1;
        List<CompletableFuture<org.bukkit.Chunk>> yuklemeler = new ArrayList<>();
        for (int x = x1 >> 4; x <= x2 >> 4; x++) {
            for (int z = z1 >> 4; z <= z2 >> 4; z++) yuklemeler.add(w.getChunkAtAsync(x, z, true));
        }
        CompletableFuture.allOf(yuklemeler.toArray(new CompletableFuture[0])).whenComplete((v, hata) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (bitti.getAsBoolean()) return;
                    sonuc.accept(hata == null ? degerlendir(w, x1, z1, x2, z2, haric) : null);
                }));
    }

    // ------------------------------------------------------------------ DEĞERLENDİRME (ana thread, chunk'lar yüklü)
    /** Yüzey yüksekliği: ağaç gövdesi, yaprak ve bitkiler (çimen, çiçek, kar katmanı) sayılmaz. */
    private int zeminY(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int alt = Math.max(w.getMinHeight(), y - 30);
        while (y > alt) {
            Block b = w.getBlockAt(x, y, z);
            if (Tag.LOGS.isTagged(b.getType()) || Tag.LEAVES.isTagged(b.getType()) || b.isPassable()) y--;
            else break;
        }
        return y;
    }

    /** Bölge düz ve kuru mu? Uygunsa {en alçak, en yüksek} zemin, değilse null. */
    private int[] duzluk(World w, int x1, int z1, int x2, int z2) {
        List<Integer> yukseklikler = new ArrayList<>();
        int sivi = 0;
        for (int x = x1; x <= x2; x += 4) {
            for (int z = z1; z <= z2; z += 4) {
                int y = zeminY(w, x, z);
                Block b = w.getBlockAt(x, y, z);
                Material m = b.getType();
                if (b.isLiquid() || m == Material.ICE || m == Material.BLUE_ICE || m == Material.PACKED_ICE) sivi++;
                yukseklikler.add(y);
            }
        }
        int toplam = yukseklikler.size();
        if (toplam == 0 || (double) sivi / toplam > maxSivi()) return null;
        Collections.sort(yukseklikler);
        // Uç değerler (tek tük kaya, çukur) hariç: %5 - %95 aralığı
        if (yukseklikler.get((int) (toplam * 0.95)) - yukseklikler.get((int) (toplam * 0.05)) > maxFark()) return null;
        return new int[]{yukseklikler.get(0), yukseklikler.get(toplam - 1)};
    }

    private Object duzlukHatasi(World w, int x1, int z1, int x2, int z2) {
        return duzluk(w, x1, z1, x2, z2) == null ? "uygun değil" : null;
    }

    private Alan degerlendir(World w, int x1, int z1, int x2, int z2, Savas haric) {
        int[] uc = duzluk(w, x1, z1, x2, z2);
        if (uc == null) return null;
        for (int x = x1; x <= x2; x += 8) {
            for (int z = z1; z <= z2; z += 8) {
                Location l = new Location(w, x, zeminY(w, x, z), z);
                if (plugin.guvenliBolgeler().guvenliMi(l) || claimVar(l)) return null;
            }
        }
        for (Savas x : plugin.savas().aktifSavaslar()) {
            if (x == haric || x.arena.pos1 == null || x.arena.pos2 == null || !w.equals(x.arena.pos1.getWorld())) continue;
            if (x1 <= Math.max(x.arena.pos1.getX(), x.arena.pos2.getX()) && x2 >= Math.min(x.arena.pos1.getX(), x.arena.pos2.getX())
                    && z1 <= Math.max(x.arena.pos1.getZ(), x.arena.pos2.getZ()) && z2 >= Math.min(x.arena.pos1.getZ(), x.arena.pos2.getZ())) return null;
        }
        int cz = (z1 + z2) / 2, cx = (x1 + x2) / 2, ara = Math.min(40, boyut() / 2 - 8);
        Location s1 = zemin(w, cx - ara, cz, 1), s2 = zemin(w, cx + ara, cz, -1);
        if (s1 == null || s2 == null) return null;
        s1.setYaw(-90f); // +X yönüne (rakibe) bakar
        s2.setYaw(90f);
        return new Alan(w, x1, z1, x2, z2, uc[0], uc[1], s1, s2);
    }

    /** x,z civarında üstü açık, katı ve sıvı olmayan zemin üstü konum (yon: merkeze doğru arama yönü). */
    private Location zemin(World w, int x, int z, int yon) {
        for (int dx = 0; dx <= 8; dx += 2) {
            for (int dz = -6; dz <= 6; dz += 2) {
                int bx = x + dx * yon, bz = z + dz;
                int y = zeminY(w, bx, bz);
                Block b = w.getBlockAt(bx, y, bz);
                if (b.isLiquid() || !b.getType().isSolid()) continue;
                Block ust1 = b.getRelative(0, 1, 0), ust2 = b.getRelative(0, 2, 0);
                if (!ust1.isPassable() || !ust2.isPassable() || ust1.isLiquid() || ust2.isLiquid()) continue;
                return new Location(w, bx + 0.5, y + 1, bz + 0.5);
            }
        }
        return null;
    }

    private boolean claimVar(Location l) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) return false;
            return me.ryanhamshire.GriefPrevention.GriefPrevention.instance.dataStore.getClaimAt(l, true, null) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ SAVAŞ SIRASINDA
    /** Savaş bitince: yüklü tutulan chunk'lar bırakılır. */
    public void birak(Savas s) {
        for (Location l : new Location[]{s.arena.spawn1, s.arena.spawn2}) {
            if (l != null && l.getWorld() != null) l.getWorld().removePluginChunkTicket(l.getBlockX() >> 4, l.getBlockZ() >> 4, plugin);
        }
    }

    /** Savaş başında alandaki düşman yaratıklar temizlenir. */
    public void canavarlariTemizle(Savas s) {
        if (s.arena.pos1 == null || s.arena.pos2 == null) return;
        World w = s.arena.pos1.getWorld();
        for (Entity e : w.getNearbyEntities(org.bukkit.util.BoundingBox.of(s.arena.pos1, s.arena.pos2), x -> x instanceof Monster)) e.remove();
    }

    /** Savaşanlara özel görünür sınır duvarı (sadece onlar görür). */
    public void sinirGoster(Player p, Savas s) {
        if (s.arena.pos1 == null || s.arena.pos2 == null) return;
        org.bukkit.WorldBorder wb = Bukkit.createWorldBorder();
        double minX = Math.min(s.arena.pos1.getX(), s.arena.pos2.getX()), maxX = Math.max(s.arena.pos1.getX(), s.arena.pos2.getX());
        double minZ = Math.min(s.arena.pos1.getZ(), s.arena.pos2.getZ()), maxZ = Math.max(s.arena.pos1.getZ(), s.arena.pos2.getZ());
        wb.setCenter((minX + maxX + 1) / 2.0, (minZ + maxZ + 1) / 2.0);
        wb.setSize(maxX - minX + 1);
        wb.setWarningDistance(3);
        p.setWorldBorder(wb);
    }

    public void sinirKaldir(Player p) {
        p.setWorldBorder(null);
    }

    /** Duyuru metni için. */
    public static String konumMetni(Savas s) {
        if (s.arena.pos1 == null || s.arena.pos2 == null) return "?";
        return (int) ((s.arena.pos1.getX() + s.arena.pos2.getX()) / 2) + ", " + (int) ((s.arena.pos1.getZ() + s.arena.pos2.getZ()) / 2);
    }
}
