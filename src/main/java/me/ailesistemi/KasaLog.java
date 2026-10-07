package me.ailesistemi;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.logging.Level;

import me.ailesistemi.model.Aile;

/**
 * Kasa, aidat ve yönetim işlemlerinin kalıcı kaydı: plugins/AileSistemi/loglar/aile-YYYY-MM.log
 * Dosyaya yazma arka planda, sırayla yapılır.
 */
public class KasaLog {

    private final AileSistemi plugin;

    public KasaLog(AileSistemi plugin) {
        this.plugin = plugin;
    }

    public void yaz(Aile aile, String kim, String islem, String detay) {
        String satir = "[" + Zaman.tarih(System.currentTimeMillis()) + "] [" + (aile != null ? aile.isim : "-") + "] "
                + kim + " | " + islem + (detay == null || detay.isEmpty() ? "" : " | " + detay) + System.lineSeparator();
        LocalDate bugun = LocalDate.now();
        File dosya = new File(plugin.getDataFolder(), "loglar/aile-" + bugun.getYear() + "-" + String.format("%02d", bugun.getMonthValue()) + ".log");
        plugin.arkaPlanda(() -> {
            try {
                Files.createDirectories(dosya.getParentFile().toPath());
                Files.writeString(dosya.toPath(), satir, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Aile logu yazılamadı", e);
            }
        });
    }
}
