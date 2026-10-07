package me.klansistemi.savas;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.boss.BossBar;

/** Süren (hazırlıkta ya da aktif) bir arena düellosu. */
public class Savas {

    public enum Durum { HAZIRLIK, AKTIF }

    public final UUID klanA;   // Teklifi gönderen (spawn1)
    public final UUID klanB;   // Teklifi kabul eden (spawn2)
    public final Arena arena;
    public final long kilitBaslangic;

    public Durum durum = Durum.HAZIRLIK;
    public long hazirlikBitis;
    public long baslangic;
    public long bitis;

    public int puanA, puanB;
    public final Map<UUID, UUID> katilimcilar = new HashMap<>();     // oyuncu -> klanı
    public final Map<UUID, Long> sonOlum = new HashMap<>();           // kurban -> son öldürülüp puan verildiği an
    public final Map<UUID, Long> cikislar = new HashMap<>();          // oyundan çıkan katılımcı -> çıkış anı
    public final Set<UUID> korumada = new HashSet<>();                // yeniden doğduktan sonra bekleyenler
    public BossBar bar;

    public Savas(UUID klanA, UUID klanB, Arena arena, long simdi) {
        this.klanA = klanA;
        this.klanB = klanB;
        this.arena = arena;
        this.kilitBaslangic = simdi;
    }

    public boolean taraf(UUID klan) { return klan.equals(klanA) || klan.equals(klanB); }

    public UUID rakip(UUID klan) { return klan.equals(klanA) ? klanB : klanA; }

    public int katilimciSayisi(UUID klan) {
        int n = 0;
        for (UUID a : katilimcilar.values()) if (a.equals(klan)) n++;
        return n;
    }

    /** Oyunda olan (çıkış süresi beklemeyen) katılımcı sayısı. */
    public int aktifKatilimci(UUID klan) {
        int n = 0;
        for (Map.Entry<UUID, UUID> e : katilimcilar.entrySet()) {
            if (e.getValue().equals(klan) && !cikislar.containsKey(e.getKey())) n++;
        }
        return n;
    }

    public void puanEkle(UUID klan, int puan) {
        if (klan.equals(klanA)) puanA += puan;
        else puanB += puan;
    }

    public int puan(UUID klan) { return klan.equals(klanA) ? puanA : puanB; }
}
