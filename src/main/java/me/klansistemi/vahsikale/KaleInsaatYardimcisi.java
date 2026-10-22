package me.klansistemi.vahsikale;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Chain;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;

/**
 * Vahşi Kale'nin FİZİKSEL yapısını sıfırdan, saf Bukkit block API ile (WorldEdit'siz) inşa eder.
 * Bastion Remnant'a benzeyen, tamamen kapalı (çatılı, uçarak girilemeyen) bir dikdörtgen prizma:
 * blackstone ailesi + zincir/fener + tek demir kapı girişi. Senkron çalışır (admin komutu veya
 * otomatik üretim tetikleyicisi tarafından çağrılır) - ağır olabileceğinden sık çağrılmaz.
 *
 * Kullanıcı isteğiyle genişletildi: içi artık boş bir tek oda değil, KAT KAT (seviyeye göre 1-3 kat)
 * ve her katta ODA ODA, LABİRENT gibi bölümlenmiş bir iç mekan. Katlar arası tek bir merdiven/
 * ladder bacası ile bağlanır. Lider (taht), girişten labirent-mesafesi en uzak hücrede ve EN ÜST
 * katta duruyor - "boss odası" hissi için.
 */
public final class KaleInsaatYardimcisi {

    private KaleInsaatYardimcisi() { }

    /** İnşaat sonucu: NPC doğurma/sandık/respawn için gereken konumlar + yapı koruması için sınır kutusu. */
    public static final class Sonuc {
        public Location girisIci;              // Respawn / "merkez" olarak kullanılır (kapının hemen içi)
        public Location liderKonumu;            // Taht platformu (en üst kat, labirentin en uzak odası)
        public final List<Location> nobetciKonumlari = new ArrayList<>();
        public final List<Location> sandikKonumlari = new ArrayList<>();
        // Yapının tam sınırları (blok koordinatları, dahil) - fethedilene kadar blok kırma/koyma
        // koruması için (etrafındaki düzleştirilmiş platform payı da dahil, bkz. kenarPay).
        public int sinirMinX, sinirMaxX, sinirMinY, sinirMaxY, sinirMinZ, sinirMaxZ;
    }

    /**
     * @param merkez   Yapının ortalanacağı yaklaşık konum (Y önemsiz, zemin otomatik bulunur)
     * @param seviye   1-5, boyutu, kat sayısını ve labirent boyutunu belirler
     * @param klanTuru "VINDICATOR" (ham/vahşi - kaba taş+moss, süslemesiz) ya da "INSAN" (cilalı
     *                 blackstone+gilded süsleme) - kale her klan türüne göre farklı görünsün diye.
     */
    public static Sonuc insaEt(Location merkez, int seviye, Random rng, String klanTuru) {
        boolean vahsi = "VINDICATOR".equals(klanTuru);
        World dunya = merkez.getWorld();

        // Eskiden tek kat/tek oda idi; artık seviye arttıkça hem taban alanı hem kat sayısı büyüyor -
        // "daha büyük bir bastion tarzı, kat kat, oda oda labirent" isteğiyle genişletildi.
        int genislik = 24 + (seviye - 1) * 6;      // X ekseni: 24..48
        int derinlik = 20 + (seviye - 1) * 6;      // Z ekseni: 20..44
        int katYuksekligi = 6;                     // her kat için iç yükseklik (sabit - oda/koridor ferahlığı)
        int katSayisi = 1 + (seviye - 1) / 2;       // seviye 1-2: 1 kat, 3-4: 2 kat, 5: 3 kat
        int yukseklik = katSayisi * (katYuksekligi + 1) - 1; // toplam iç yükseklik (ara kat döşemeleri dahil)
        int merkezX = merkez.getBlockX();
        int merkezZ = merkez.getBlockZ();
        int xMin = merkezX - genislik / 2, xMax = xMin + genislik - 1;
        int zMin = merkezZ - derinlik / 2, zMax = zMin + derinlik - 1;
        int kenarPay = 3;

        // 1) Zemin seviyesini bul: footprint + pay alanındaki en yüksek doğal bloğa göre düz bir taban belirle
        int tabanY = Integer.MIN_VALUE;
        for (int x = xMin - kenarPay; x <= xMax + kenarPay; x++) {
            for (int z = zMin - kenarPay; z <= zMax + kenarPay; z++) {
                dunya.getChunkAt(x >> 4, z >> 4).load();
                int y = dunya.getHighestBlockYAt(x, z);
                if (y > tabanY) tabanY = y;
            }
        }
        int y0 = tabanY + 1; // yapının oturduğu kat

        // 2) Üstünü (ağaç/tepe) temizle, altını (çukur varsa) doldur -> düz bir platform garantile
        for (int x = xMin - kenarPay; x <= xMax + kenarPay; x++) {
            for (int z = zMin - kenarPay; z <= zMax + kenarPay; z++) {
                for (int y = y0; y <= y0 + yukseklik + 10; y++) setHava(dunya, x, y, z);
                for (int y = y0 - 1; y >= y0 - 6; y--) {
                    Block b = dunya.getBlockAt(x, y, z);
                    if (b.getType() == Material.AIR || b.getType() == Material.WATER || b.getType() == Material.LAVA
                            || b.getType() == Material.CAVE_AIR) {
                        b.setType(Material.BLACKSTONE, false);
                    }
                }
            }
        }

        // İnsan klanları: cilalı/düzgün blackstone ailesi. Vindicator klanları: kaba taş + yosun,
        // vahşi/düzensiz bir fortress hissi - ikisi artık görsel olarak da birbirinden ayrılıyor.
        Material[] duvarKaris = vahsi
                ? new Material[]{Material.BLACKSTONE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
                        Material.COBBLESTONE, Material.BLACKSTONE}
                : new Material[]{Material.BLACKSTONE, Material.BLACKSTONE, Material.POLISHED_BLACKSTONE,
                        Material.POLISHED_BLACKSTONE_BRICKS, Material.CRACKED_POLISHED_BLACKSTONE_BRICKS};
        Material[] icDuvarKaris = vahsi
                ? new Material[]{Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.COBBLESTONE}
                : new Material[]{Material.POLISHED_BLACKSTONE, Material.POLISHED_BLACKSTONE_BRICKS, Material.CHISELED_POLISHED_BLACKSTONE};
        Material kuleMalzeme = vahsi ? Material.COBBLESTONE : Material.GILDED_BLACKSTONE;
        Material tahtMalzeme = vahsi ? Material.MOSSY_COBBLESTONE : Material.GILDED_BLACKSTONE;
        Material tahtIsigi = vahsi ? Material.REDSTONE_TORCH : Material.END_ROD;

        // 3) Dış duvarlar (2 blok kalın, tam çevre) + tam kapalı çatı + taban
        for (int x = xMin; x <= xMax; x++) {
            for (int z = zMin; z <= zMax; z++) {
                boolean kenarX = (x == xMin || x == xMin + 1 || x == xMax || x == xMax - 1);
                boolean kenarZ = (z == zMin || z == zMin + 1 || z == zMax || z == zMax - 1);
                if (kenarX || kenarZ) {
                    for (int y = y0; y <= y0 + yukseklik; y++) {
                        dunya.getBlockAt(x, y, z).setType(rastgeleSec(duvarKaris, rng), false);
                    }
                }
                // Taban
                dunya.getBlockAt(x, y0 - 1, z).setType(Material.POLISHED_BLACKSTONE, false);
                // Tam kapalı çatı (tek kat yeter, uçarak girişi engeller)
                dunya.getBlockAt(x, y0 + yukseklik + 1, z).setType(Material.BLACKSTONE, false);
            }
        }

        // 4) 4 köşede yükselen küçük kuleler (bastion silueti)
        int kuleBoy = 4;
        int[][] koseler = {{xMin, zMin}, {xMin, zMax - kuleBoy + 1}, {xMax - kuleBoy + 1, zMin}, {xMax - kuleBoy + 1, zMax - kuleBoy + 1}};
        for (int[] kose : koseler) {
            for (int x = kose[0]; x < kose[0] + kuleBoy; x++) {
                for (int z = kose[1]; z < kose[1] + kuleBoy; z++) {
                    boolean cerceve = (x == kose[0] || x == kose[0] + kuleBoy - 1 || z == kose[1] || z == kose[1] + kuleBoy - 1);
                    if (cerceve) {
                        for (int y = y0 + yukseklik - 2; y <= y0 + yukseklik + 5; y++) {
                            dunya.getBlockAt(x, y, z).setType(kuleMalzeme, false);
                        }
                    }
                }
            }
        }

        // 5) Tek giriş: güney duvarın (zMax) ortasında demir kapı + dışarıda buton
        int kapiX1 = merkezX, kapiX2 = merkezX + 1;
        for (int dx = 0; dx <= 1; dx++) {
            int x = (dx == 0 ? kapiX1 : kapiX2);
            for (int derinlikIdx = 0; derinlikIdx < 2; derinlikIdx++) {
                int z = zMax - derinlikIdx;
                demirKapiKoy(dunya, x, y0, z);
            }
        }
        // Dışarıdan açmak için buton (kapının hemen dışında, duvar üstünde)
        Block butonBlogu = dunya.getBlockAt(kapiX1, y0 + 1, zMax + 1);
        butonBlogu.setType(Material.STONE_BUTTON, false);
        if (butonBlogu.getBlockData() instanceof Directional dir) {
            dir.setFacing(BlockFace.SOUTH);
            butonBlogu.setBlockData(dir);
        }

        // 6) İç labirent ızgarası (bkz. labirentUret) - TÜM katlarda aynı bağlantı deseni tekrarlanır,
        //    böylece kat kat üst üste tutarlı bir "labirent kulesi" hissi verir.
        int ic0X = xMin + 2, ic1X = xMax - 2;
        int ic0Z = zMin + 2, ic1Z = zMax - 2;
        int toplamIcX = ic1X - ic0X + 1;
        int toplamIcZ = ic1Z - ic0Z + 1;
        int gridX = Math.max(2, Math.min(5, toplamIcX / 7));
        int gridZ = Math.max(2, Math.min(5, toplamIcZ / 7));
        int[] sinirlarX = hucreSinirlari(ic0X, toplamIcX, gridX);
        int[] sinirlarZ = hucreSinirlari(ic0Z, toplamIcZ, gridZ);

        // Giriş hücresi: zMax'a en yakın sıra (son satır), X'te merkeze en yakın sütun.
        int girisCx = hucreBul(sinirlarX, merkezX);
        int girisCz = gridZ - 1;

        boolean[][] baglantiDogu = new boolean[gridX][gridZ];   // [cx][cz]: (cx,cz) <-> (cx+1,cz)
        boolean[][] baglantiGuney = new boolean[gridX][gridZ];  // [cx][cz]: (cx,cz) <-> (cx,cz+1)
        labirentUret(gridX, gridZ, girisCx, girisCz, rng, baglantiDogu, baglantiGuney);

        // Girişten (grafik/labirent mesafesi) en uzak hücre - taht/boss odası burada olacak.
        int[] uzakHucre = enUzakHucre(gridX, gridZ, girisCx, girisCz, baglantiDogu, baglantiGuney);
        int tahtCx = uzakHucre[0], tahtCz = uzakHucre[1];

        Sonuc sonuc = new Sonuc();
        int ustKat = katSayisi - 1;
        int[] tahtXAralik = icAralik(sinirlarX, tahtCx);
        int[] tahtZAralik = icAralik(sinirlarZ, tahtCz);
        int tahtMerkezX = (tahtXAralik[0] + tahtXAralik[1]) / 2;
        int tahtMerkezZ = (tahtZAralik[0] + tahtZAralik[1]) / 2;
        int tahtKatY = y0 + ustKat * (katYuksekligi + 1);

        // Dikey bağlantı bacası: giriş hücresinin içinde, sabit bir noktada - tüm katları aynı
        // kolonda birleştiren tek bir merdiven/ladder. Döşeme boşluğu + arkasına sağlam bir sütuna
        // tutturulmuş ladder - yön/BlockData karmaşasına girmeden güvenilir şekilde tırmanılabilir.
        int[] girisXAralik = icAralik(sinirlarX, girisCx);
        int[] girisZAralik = icAralik(sinirlarZ, girisCz);
        int bacaX = Math.max(girisXAralik[0], Math.min(girisXAralik[1] - 1, girisXAralik[0] + 2));
        int bacaZSutun = Math.max(girisZAralik[0] + 1, Math.min(girisZAralik[1], zMax - 4)); // sütun (arkada, sağlam blok)
        int bacaZMerdiven = bacaZSutun - 1;                          // ladder'ın kendisi (önde, tırmanılan hücre)
        boolean bacaVarMi = katSayisi > 1 && bacaZMerdiven > girisZAralik[0];

        // 7) Her kat için: labirent duvarları + döşeme (üst katlarda) + dekor + oda içeriği
        List<int[]> dolduOdaIndeksleri = new ArrayList<>(); // [kat, cx, cz] - sandık/nöbetçi dağıtımı için
        for (int kat = 0; kat < katSayisi; kat++) {
            int katY0 = y0 + kat * (katYuksekligi + 1); // bu katın ilk yürünebilir hava katmanı
            if (kat > 0) {
                // Ara kat döşemesi (alttaki katın tavanı = bu katın tabanı), baca kolonunda delik bırak.
                for (int x = ic0X; x <= ic1X; x++) {
                    for (int z = ic0Z; z <= ic1Z; z++) {
                        if (bacaVarMi && x == bacaX && z == bacaZMerdiven) continue; // merdiven boşluğu
                        dunya.getBlockAt(x, katY0 - 1, z).setType(Material.POLISHED_BLACKSTONE, false);
                    }
                }
            }
            // Labirent iç duvarları (bu kat için, y aralığı: katY0..katY0+katYuksekligi-1)
            for (int cx = 0; cx < gridX - 1; cx++) {
                int duvarX = sinirlarX[cx + 1]; // bu sütunun İLK bloğu, (cx,*) hücresinin payına ait duvar
                for (int cz = 0; cz < gridZ; cz++) {
                    int satirZ0 = (cz == 0) ? sinirlarZ[0] : sinirlarZ[cz] + 1;
                    int satirZ1 = sinirlarZ[cz + 1] - (cz < gridZ - 1 ? 1 : 0);
                    boolean baglantili = baglantiDogu[cx][cz];
                    gapliDuvarCiz(dunya, true, duvarX, satirZ0, satirZ1, katY0, katYuksekligi,
                            baglantili, icDuvarKaris, rng);
                }
            }
            for (int cz = 0; cz < gridZ - 1; cz++) {
                int duvarZ = sinirlarZ[cz + 1];
                for (int cx = 0; cx < gridX; cx++) {
                    int satirX0 = (cx == 0) ? sinirlarX[0] : sinirlarX[cx] + 1;
                    int satirX1 = sinirlarX[cx + 1] - (cx < gridX - 1 ? 1 : 0);
                    boolean baglantili = baglantiGuney[cx][cz];
                    gapliDuvarCiz(dunya, false, duvarZ, satirX0, satirX1, katY0, katYuksekligi,
                            baglantili, icDuvarKaris, rng);
                }
            }
            // Bu katın kendi tavanından sarkan zincir+fener (seyrek, oda hissini güçlendirir)
            int tavanY = katY0 + katYuksekligi;
            for (int x = ic0X + 3; x <= ic1X - 2; x += 6) {
                for (int z = ic0Z + 3; z <= ic1Z - 2; z += 6) {
                    zincirFenerAs(dunya, x, tavanY, z);
                }
            }
            for (int cx = 0; cx < gridX; cx++) {
                for (int cz = 0; cz < gridZ; cz++) {
                    if (kat == ustKat && cx == tahtCx && cz == tahtCz) continue; // taht odası ayrı işlenir
                    dolduOdaIndeksleri.add(new int[]{kat, cx, cz});
                }
            }
        }

        // 8) Dikey bacanın kendisi: sağlam sütun (destek) + ladder (tırmanma) - en alt kattan çatıya kadar.
        if (bacaVarMi) {
            for (int y = y0; y <= y0 + yukseklik - 1; y++) {
                dunya.getBlockAt(bacaX, y, bacaZSutun).setType(Material.POLISHED_BLACKSTONE, false);
                Block ladderBlok = dunya.getBlockAt(bacaX, y, bacaZMerdiven);
                ladderBlok.setType(Material.LADDER, false);
                if (ladderBlok.getBlockData() instanceof Directional ladderDir) {
                    // facing = tutturulduğu bloktan UZAK yön (açık/tırmanılan taraf). Sütun ladder'ın
                    // GÜNEYİNDE (bacaZSutun > bacaZMerdiven) olduğu için "uzak" yön KUZEY'dir.
                    ladderDir.setFacing(BlockFace.NORTH);
                    ladderBlok.setBlockData(ladderDir);
                }
            }
        }

        // 9) Taht platformu (en üst kat, labirentin en uzak odası) - lider burada duracak
        for (int x = tahtMerkezX - 1; x <= tahtMerkezX + 1; x++) {
            for (int z = tahtMerkezZ - 1; z <= tahtMerkezZ + 1; z++) {
                dunya.getBlockAt(x, tahtKatY, z).setType(tahtMalzeme, false);
            }
        }
        dunya.getBlockAt(tahtMerkezX, tahtKatY + 1, tahtMerkezZ).setType(tahtIsigi, false);

        sonuc.liderKonumu = new Location(dunya, tahtMerkezX + 0.5, tahtKatY + 1, tahtMerkezZ + 0.5);
        sonuc.girisIci = new Location(dunya, merkezX + 0.5, y0 + 1, zMax - 2.5);
        // Blok kırma/koyma koruması için tam yapı sınırı - yapının kendisi (taban/çatı/kule tepeleri)
        // KADAR DEĞİL, yapının etrafındaki düzleştirilmiş platform (kenarPay payı) da dahil: aksi halde
        // oyuncular yapının hemen dışındaki bu platforma blok döşeyip merdiven/köprü kurarak duvar/çatı
        // korumasını by-pass edebiliyordu.
        sonuc.sinirMinX = xMin - kenarPay; sonuc.sinirMaxX = xMax + kenarPay;
        sonuc.sinirMinY = y0 - 1; sonuc.sinirMaxY = y0 + yukseklik + 10;
        sonuc.sinirMinZ = zMin - kenarPay; sonuc.sinirMaxZ = zMax + kenarPay;

        // 10) Nöbetçi doğum noktaları: her oda (taht hariç) için 1-2 nöbetçi, hedef sayıya ulaşana kadar
        //     rastgele odalar seçilir (bazı odalar boş kalabilir - labirentte keşif/gerilim için iyi).
        int nobetciHedef = Math.max(6, seviye * 4);
        java.util.Collections.shuffle(dolduOdaIndeksleri, rng);
        int eklenenNobetci = 0;
        int odaIdx = 0;
        while (eklenenNobetci < nobetciHedef && !dolduOdaIndeksleri.isEmpty()) {
            int[] oda = dolduOdaIndeksleri.get(odaIdx % dolduOdaIndeksleri.size());
            int kat = oda[0], cx = oda[1], cz = oda[2];
            int odaY = y0 + kat * (katYuksekligi + 1) + 1;
            int[] ox2 = icAralik(sinirlarX, cx);
            int[] oz2 = icAralik(sinirlarZ, cz);
            int ox = ox2[0] + rng.nextInt(Math.max(1, ox2[1] - ox2[0] + 1));
            int oz = oz2[0] + rng.nextInt(Math.max(1, oz2[1] - oz2[0] + 1));
            sonuc.nobetciKonumlari.add(new Location(dunya, ox + 0.5, odaY, oz + 0.5));
            eklenenNobetci++;
            odaIdx++;
            if (odaIdx > dolduOdaIndeksleri.size() * 3) break; // güvenlik: sonsuz döngü olmasın
        }

        // 11) Sandıklar: birkaç rastgele odanın köşesine (taht hariç) - labirent boyunca dağınık.
        int sandikSayisi = Math.min(6, 2 + seviye);
        for (int i = 0; i < sandikSayisi && i < dolduOdaIndeksleri.size(); i++) {
            int[] oda = dolduOdaIndeksleri.get(i);
            int kat = oda[0], cx = oda[1], cz = oda[2];
            int odaY = y0 + kat * (katYuksekligi + 1);
            int[] ox2 = icAralik(sinirlarX, cx);
            int[] oz2 = icAralik(sinirlarZ, cz);
            Block sandikBlok = dunya.getBlockAt(ox2[0], odaY, oz2[0]);
            sandikBlok.setType(Material.CHEST, false);
            sonuc.sandikKonumlari.add(sandikBlok.getLocation());
        }

        return sonuc;
    }

    /** Hücre idx'in duvar kolonu HARİÇ gerçek yürünebilir [başlangıç, bitiş] aralığı: idx=0 için
     *  tam [sinirlar[0], sinirlar[1]-1], idx>0 için [sinirlar[idx]+1, sinirlar[idx+1]-1] - hücrenin
     *  İLK kolonu önceki hücreyle paylaşılan duvara ayrıldığı için atlanır (bkz. gapliDuvarCiz'in
     *  kullandığı aynı kural - oda içeriği/merdiven buna göre konumlandırılmalı, aksi halde nöbetçi/
     *  sandık bir duvar kolonunun içinde doğabilir). */
    private static int[] icAralik(int[] sinirlar, int idx) {
        int baslangic = (idx == 0) ? sinirlar[0] : sinirlar[idx] + 1;
        int bitis = sinirlar[idx + 1] - 1;
        if (bitis < baslangic) bitis = baslangic; // dejenere küçük hücre güvenliği
        return new int[]{baslangic, bitis};
    }

    /** Bir katın X ya da Z ekseninde gridSayisi hücreye böldüğü sınır noktalarını döner
     *  (sinirlar[i]..sinirlar[i+1]-1 = i'inci hücrenin TAM aralığı, komşu hücreyle çakışmaz). */
    private static int[] hucreSinirlari(int baslangic, int toplam, int gridSayisi) {
        int[] sinirlar = new int[gridSayisi + 1];
        for (int i = 0; i <= gridSayisi; i++) {
            sinirlar[i] = baslangic + (int) Math.round(i * (toplam / (double) gridSayisi));
        }
        sinirlar[gridSayisi] = baslangic + toplam; // yuvarlama artığını son hücreye garanti ver
        return sinirlar;
    }

    private static int hucreBul(int[] sinirlar, int koordinat) {
        for (int i = 0; i < sinirlar.length - 1; i++) {
            if (koordinat >= sinirlar[i] && koordinat < sinirlar[i + 1]) return i;
        }
        return Math.max(0, sinirlar.length - 2);
    }

    /** Randomize edilmiş DFS ile bir yayılma ağacı (spanning tree) üretir - her hücre girişten
     *  ulaşılabilir, döngüsüz tek-yol bir labirent. baglantiDogu/baglantiGuney true olan kenarlarda
     *  duvar yerine geçit (gap) bırakılır - bkz. çağıran kodda gapliDuvarCiz. */
    private static void labirentUret(int gridX, int gridZ, int baslaCx, int baslaCz, Random rng,
            boolean[][] baglantiDogu, boolean[][] baglantiGuney) {
        boolean[][] ziyaret = new boolean[gridX][gridZ];
        Deque<int[]> yigin = new ArrayDeque<>();
        yigin.push(new int[]{baslaCx, baslaCz});
        ziyaret[baslaCx][baslaCz] = true;
        while (!yigin.isEmpty()) {
            int[] simdiki = yigin.peek();
            int cx = simdiki[0], cz = simdiki[1];
            List<int[]> komsular = new ArrayList<>();
            if (cx > 0 && !ziyaret[cx - 1][cz]) komsular.add(new int[]{cx - 1, cz, 0});
            if (cx < gridX - 1 && !ziyaret[cx + 1][cz]) komsular.add(new int[]{cx + 1, cz, 1});
            if (cz > 0 && !ziyaret[cx][cz - 1]) komsular.add(new int[]{cx, cz - 1, 2});
            if (cz < gridZ - 1 && !ziyaret[cx][cz + 1]) komsular.add(new int[]{cx, cz + 1, 3});
            if (komsular.isEmpty()) { yigin.pop(); continue; }
            int[] secilen = komsular.get(rng.nextInt(komsular.size()));
            int ncx = secilen[0], ncz = secilen[1], yon = secilen[2];
            switch (yon) {
                case 0 -> baglantiDogu[ncx][ncz] = true;      // (ncx,ncz) doğusu = (cx,cz)
                case 1 -> baglantiDogu[cx][cz] = true;        // (cx,cz) doğusu = (ncx,ncz)
                case 2 -> baglantiGuney[ncx][ncz] = true;     // (ncx,ncz) güneyi = (cx,cz)
                default -> baglantiGuney[cx][cz] = true;      // (cx,cz) güneyi = (ncx,ncz)
            }
            ziyaret[ncx][ncz] = true;
            yigin.push(new int[]{ncx, ncz});
        }
        // Birkaç ekstra rastgele bağlantı (kısayol/döngü) - saf tek-yol labirent sıkıcı/can sıkıcı
        // olabilir, %18 ihtimalle zaten bitişik hücreler arasına ek geçit açılır.
        for (int cx = 0; cx < gridX - 1; cx++) {
            for (int cz = 0; cz < gridZ; cz++) {
                if (!baglantiDogu[cx][cz] && rng.nextDouble() < 0.18) baglantiDogu[cx][cz] = true;
            }
        }
        for (int cx = 0; cx < gridX; cx++) {
            for (int cz = 0; cz < gridZ - 1; cz++) {
                if (!baglantiGuney[cx][cz] && rng.nextDouble() < 0.18) baglantiGuney[cx][cz] = true;
            }
        }
    }

    /** BFS ile girişten grafik (hücre-adımı) mesafesi en uzak hücreyi bulur - taht/boss odası. */
    private static int[] enUzakHucre(int gridX, int gridZ, int baslaCx, int baslaCz,
            boolean[][] baglantiDogu, boolean[][] baglantiGuney) {
        int[][] mesafe = new int[gridX][gridZ];
        for (int[] satir : mesafe) java.util.Arrays.fill(satir, -1);
        Deque<int[]> kuyruk = new ArrayDeque<>();
        mesafe[baslaCx][baslaCz] = 0;
        kuyruk.add(new int[]{baslaCx, baslaCz});
        int[] enUzak = {baslaCx, baslaCz};
        while (!kuyruk.isEmpty()) {
            int[] simdiki = kuyruk.poll();
            int cx = simdiki[0], cz = simdiki[1];
            if (mesafe[cx][cz] > mesafe[enUzak[0]][enUzak[1]]) enUzak = simdiki;
            if (cx > 0 && baglantiDogu[cx - 1][cz] && mesafe[cx - 1][cz] < 0) {
                mesafe[cx - 1][cz] = mesafe[cx][cz] + 1; kuyruk.add(new int[]{cx - 1, cz});
            }
            if (cx < gridX - 1 && baglantiDogu[cx][cz] && mesafe[cx + 1][cz] < 0) {
                mesafe[cx + 1][cz] = mesafe[cx][cz] + 1; kuyruk.add(new int[]{cx + 1, cz});
            }
            if (cz > 0 && baglantiGuney[cx][cz - 1] && mesafe[cx][cz - 1] < 0) {
                mesafe[cx][cz - 1] = mesafe[cx][cz] + 1; kuyruk.add(new int[]{cx, cz - 1});
            }
            if (cz < gridZ - 1 && baglantiGuney[cx][cz] && mesafe[cx][cz + 1] < 0) {
                mesafe[cx][cz + 1] = mesafe[cx][cz] + 1; kuyruk.add(new int[]{cx, cz + 1});
            }
        }
        return enUzak;
    }

    /** Bir labirent duvar hattını (dikey ise sabitX, yatay ise sabitZ) çizer - bağlantılıysa ortalanmış
     *  bir geçit (gap) bırakır, değilse tüm hat boyunca katın yüksekliğince dolu duvar örer. */
    private static void gapliDuvarCiz(World dunya, boolean dikey, int sabitKoordinat, int aralikBaslangic,
            int aralikBitis, int katY0, int katYuksekligi, boolean baglantili, Material[] malzemeler, Random rng) {
        int uzunluk = aralikBitis - aralikBaslangic + 1;
        if (uzunluk <= 0) return;
        int bosGenislik = baglantili ? Math.min(3, Math.max(1, uzunluk - 2)) : 0;
        int bosBaslangic = aralikBaslangic + Math.max(0, (uzunluk - bosGenislik) / 2);
        int bosBitis = bosBaslangic + bosGenislik - 1;
        for (int degisken = aralikBaslangic; degisken <= aralikBitis; degisken++) {
            boolean bosluk = baglantili && degisken >= bosBaslangic && degisken <= bosBitis;
            if (bosluk) continue;
            for (int y = katY0; y <= katY0 + katYuksekligi - 1; y++) {
                if (dikey) {
                    dunya.getBlockAt(sabitKoordinat, y, degisken).setType(rastgeleSec(malzemeler, rng), false);
                } else {
                    dunya.getBlockAt(degisken, y, sabitKoordinat).setType(rastgeleSec(malzemeler, rng), false);
                }
            }
        }
    }

    private static void setHava(World dunya, int x, int y, int z) {
        Block b = dunya.getBlockAt(x, y, z);
        if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
    }

    private static Material rastgeleSec(Material[] secenekler, Random rng) {
        return secenekler[rng.nextInt(secenekler.length)];
    }

    private static void demirKapiKoy(World dunya, int x, int y, int z) {
        Block alt = dunya.getBlockAt(x, y, z);
        Block ust = dunya.getBlockAt(x, y + 1, z);
        alt.setType(Material.IRON_DOOR, false);
        ust.setType(Material.IRON_DOOR, false);
        if (alt.getBlockData() instanceof Door altKapi) {
            altKapi.setHalf(Door.Half.BOTTOM);
            altKapi.setFacing(BlockFace.NORTH);
            alt.setBlockData(altKapi);
        }
        if (ust.getBlockData() instanceof Door ustKapi) {
            ustKapi.setHalf(Door.Half.TOP);
            ustKapi.setFacing(BlockFace.NORTH);
            ust.setBlockData(ustKapi);
        }
    }

    private static void zincirFenerAs(World dunya, int x, int tavanY, int z) {
        Block zincir = dunya.getBlockAt(x, tavanY - 1, z);
        zincir.setType(Material.IRON_CHAIN, false);
        if (zincir.getBlockData() instanceof Chain c) {
            c.setAxis(org.bukkit.Axis.Y);
            zincir.setBlockData(c);
        }
        Block fenerBlok = dunya.getBlockAt(x, tavanY - 2, z);
        fenerBlok.setType(Material.SOUL_LANTERN, false);
        BlockData data = fenerBlok.getBlockData();
        if (data instanceof Lantern l) {
            l.setHanging(true);
            fenerBlok.setBlockData(l);
        }
        if (data instanceof Lightable lit) {
            lit.setLit(true);
            fenerBlok.setBlockData(data);
        }
    }
}
