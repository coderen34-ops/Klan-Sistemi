package me.klansistemi;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class Para {

    private static final DecimalFormat BICIM = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("tr-TR")));

    private Para() {}

    /** 50000 -> "$50.000", 12.5 -> "$12,5" */
    public static String yaz(double miktar) {
        synchronized (BICIM) {
            return "$" + BICIM.format(miktar);
        }
    }

    public static double kurus(double miktar) {
        return Math.round(miktar * 100.0) / 100.0;
    }

    /** Oyuncunun yazdığı miktar. Geçersiz (sayı değil, NaN, sonsuz, 0 veya negatif) ise -1. */
    public static double oku(String metin) {
        try {
            double d = Double.parseDouble(metin.trim());
            if (!Double.isFinite(d) || d <= 0) return -1;
            return kurus(d);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
