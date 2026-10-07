package me.ailesistemi.savas;

import java.util.UUID;

/** Geçmiş savaş kaydı (panelde "Geçmiş Savaşlar" ve admin logu için). */
public class SavasKaydi {
    public final UUID aileA, aileB;
    public final String isimA, isimB;
    public final int puanA, puanB;
    public final UUID kazanan;          // null = berabere / iptal
    public final String sonuc;          // "KAZANDI", "BERABERE", "IPTAL"
    public final double ganimetPara;
    public final int ganimetEsya;
    public final long zaman;
    public final long sure;

    public SavasKaydi(UUID aileA, UUID aileB, String isimA, String isimB, int puanA, int puanB, UUID kazanan,
                      String sonuc, double ganimetPara, int ganimetEsya, long zaman, long sure) {
        this.aileA = aileA;
        this.aileB = aileB;
        this.isimA = isimA;
        this.isimB = isimB;
        this.puanA = puanA;
        this.puanB = puanB;
        this.kazanan = kazanan;
        this.sonuc = sonuc;
        this.ganimetPara = ganimetPara;
        this.ganimetEsya = ganimetEsya;
        this.zaman = zaman;
        this.sure = sure;
    }
}
