package me.klansistemi;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.logging.Level;

import me.klansistemi.model.Klan;

/**
 * Kasa, aidat ve yönetim işlemlerinin kalıcı kaydı: plugins/KlanSistemi/loglar/klan-YYYY-MM.log
 * Dosyaya yazma arka planda, sırayla yapılır.
 */
public class KasaLog {

    private final KlanSistemi plugin;

    public KasaLog(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    public void yaz(Klan klan, String kim, String islem, String detay) {
        String satir = "[" + Zaman.tarih(System.currentTimeMillis()) + "] [" + (klan != null ? klan.isim : "-") + "] "
                + kim + " | " + islem + (detay == null || detay.isEmpty() ? "" : " | " + detay) + System.lineSeparator();
        LocalDate bugun = LocalDate.now();
        File dosya = new File(plugin.getDataFolder(), "loglar/klan-" + bugun.getYear() + "-" + String.format("%02d", bugun.getMonthValue()) + ".log");
        plugin.arkaPlanda(() -> {
            try {
                Files.createDirectories(dosya.getParentFile().toPath());
                Files.writeString(dosya.toPath(), satir, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Klan logu yazılamadı", e);
            }
        });
    }
}
