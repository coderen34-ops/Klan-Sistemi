package me.klansistemi.savas;

import java.util.UUID;

/** Geçmiş savaş kaydı (panelde "Geçmiş Savaşlar" ve admin logu için). */
public class SavasKaydi {
    public final UUID klanA, klanB;
    public final String isimA, isimB;
    public final int puanA, puanB;
    public final UUID kazanan;          // null = berabere / iptal
    public final String sonuc;          // "KAZANDI", "BERABERE", "IPTAL"
    public final double ganimetPara;
    public final int ganimetEsya;
    public final int ganimetBuyu;       // Ele geçirilen büyülü (klan katmanı) eşya sayısı
    public final long zaman;
    public final long sure;

    public SavasKaydi(UUID klanA, UUID klanB, String isimA, String isimB, int puanA, int puanB, UUID kazanan,
                      String sonuc, double ganimetPara, int ganimetEsya, int ganimetBuyu, long zaman, long sure) {
        this.klanA = klanA;
        this.klanB = klanB;
        this.isimA = isimA;
        this.isimB = isimB;
        this.puanA = puanA;
        this.puanB = puanB;
        this.kazanan = kazanan;
        this.sonuc = sonuc;
        this.ganimetPara = ganimetPara;
        this.ganimetEsya = ganimetEsya;
        this.ganimetBuyu = ganimetBuyu;
        this.zaman = zaman;
        this.sure = sure;
    }
}
