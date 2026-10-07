package me.ailesistemi;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class Zaman {

    public static final long SAAT = 60L * 60 * 1000;
    public static final long GUN = 24 * SAAT;
    private static final DateTimeFormatter TARIH = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private Zaman() {}

    public static String tarih(long ms) {
        return TARIH.format(Instant.ofEpochMilli(ms));
    }

    /** 93784000 -> "1 gün 2 saat" ; 600000 -> "10 dakika" */
    public static String sure(long ms) {
        long dk = Math.max(0, ms) / 60000L;
        long gun = dk / 1440, saat = (dk % 1440) / 60, dakika = dk % 60;
        if (gun > 0) return gun + " gün " + saat + " saat";
        if (saat > 0) return saat + " saat " + dakika + " dakika";
        return Math.max(1, dakika) + " dakika";
    }
}
