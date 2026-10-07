package me.ailesistemi.savas;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.boss.BossBar;

/** Süren (hazırlıkta ya da aktif) bir arena düellosu. */
public class Savas {

    public enum Durum { HAZIRLIK, AKTIF }

    public final UUID aileA;   // Teklifi gönderen (spawn1)
    public final UUID aileB;   // Teklifi kabul eden (spawn2)
    public final Arena arena;
    public final long kilitBaslangic;

    public Durum durum = Durum.HAZIRLIK;
    public long hazirlikBitis;
    public long baslangic;
    public long bitis;

    public int puanA, puanB;
    public final Map<UUID, UUID> katilimcilar = new HashMap<>();     // oyuncu -> ailesi
    public final Map<UUID, Long> sonOlum = new HashMap<>();           // kurban -> son öldürülüp puan verildiği an
    public final Map<UUID, Long> cikislar = new HashMap<>();          // oyundan çıkan katılımcı -> çıkış anı
    public final Set<UUID> korumada = new HashSet<>();                // yeniden doğduktan sonra bekleyenler
    public BossBar bar;

    public Savas(UUID aileA, UUID aileB, Arena arena, long simdi) {
        this.aileA = aileA;
        this.aileB = aileB;
        this.arena = arena;
        this.kilitBaslangic = simdi;
    }

    public boolean taraf(UUID aile) { return aile.equals(aileA) || aile.equals(aileB); }

    public UUID rakip(UUID aile) { return aile.equals(aileA) ? aileB : aileA; }

    public int katilimciSayisi(UUID aile) {
        int n = 0;
        for (UUID a : katilimcilar.values()) if (a.equals(aile)) n++;
        return n;
    }

    /** Oyunda olan (çıkış süresi beklemeyen) katılımcı sayısı. */
    public int aktifKatilimci(UUID aile) {
        int n = 0;
        for (Map.Entry<UUID, UUID> e : katilimcilar.entrySet()) {
            if (e.getValue().equals(aile) && !cikislar.containsKey(e.getKey())) n++;
        }
        return n;
    }

    public void puanEkle(UUID aile, int puan) {
        if (aile.equals(aileA)) puanA += puan;
        else puanB += puan;
    }

    public int puan(UUID aile) { return aile.equals(aileA) ? puanA : puanB; }
}
