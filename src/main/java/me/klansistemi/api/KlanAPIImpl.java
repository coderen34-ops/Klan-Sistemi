package me.klansistemi.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.klansistemi.IliskiManager;
import me.klansistemi.KlanSistemi;
import me.klansistemi.model.Gorus;
import me.klansistemi.model.IliskiDurumu;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;

/** KlanAPI uygulaması. Tüm çağrılar ana thread'de yapılmalıdır; oyuncu gerektirmez. */
public class KlanAPIImpl implements KlanAPI {

    private final KlanSistemi plugin;

    public KlanAPIImpl(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    private static KlanBilgisi bilgi(Klan k) {
        List<UyeBilgisi> uyeler = new ArrayList<>();
        for (KlanUyesi u : k.uyeler.values()) uyeler.add(new UyeBilgisi(u.uuid, u.isim, u.rol.name(), u.katilma));
        return new KlanBilgisi(k.id, k.isim, k.kurulus, k.kasa, k.prestij, uyeler);
    }

    private static GorusBilgisi bilgi(Gorus g) {
        return new GorusBilgisi(g.yazanKlan, g.hedefKlan, g.durum.name(), g.not, g.yazan, g.zaman);
    }

    @Override
    public List<KlanBilgisi> klanlar() {
        List<KlanBilgisi> r = new ArrayList<>();
        for (Klan k : plugin.klanManager().klanlar()) r.add(bilgi(k));
        return r;
    }

    @Override
    public KlanBilgisi klanBul(String ad) {
        Klan k = ad == null ? null : plugin.klanManager().klanBul(ad);
        return k == null ? null : bilgi(k);
    }

    @Override
    public KlanBilgisi oyuncununKlani(UUID oyuncu) {
        Klan k = plugin.klanManager().oyuncununKlani(oyuncu);
        return k == null ? null : bilgi(k);
    }

    @Override
    public List<GorusBilgisi> tumGorusler() {
        List<GorusBilgisi> r = new ArrayList<>();
        for (Map<UUID, Gorus> m : plugin.iliski().tumGorusler().values())
            for (Gorus g : m.values()) r.add(bilgi(g));
        return r;
    }

    @Override
    public List<GorusBilgisi> gorusler(UUID klanId) {
        List<GorusBilgisi> r = new ArrayList<>();
        Map<UUID, Gorus> m = plugin.iliski().tumGorusler().get(klanId);
        if (m != null) for (Gorus g : m.values()) r.add(bilgi(g));
        return r;
    }

    @Override
    public List<IliskiBilgisi> iliskiler(UUID klanId) {
        List<IliskiBilgisi> r = new ArrayList<>();
        for (Klan diger : plugin.klanManager().klanlar()) {
            if (diger.id.equals(klanId)) continue;
            IliskiDurumu d = plugin.iliski().iliski(klanId, diger.id);
            if (d == IliskiDurumu.DOST || d == IliskiDurumu.HUSUMET) r.add(new IliskiBilgisi(klanId, diger.id, d.name()));
        }
        return r;
    }

    @Override
    public GorusSonucu gorusYaz(UUID yazanOyuncu, String hedefKlan, String durum, String not) {
        if (yazanOyuncu == null || hedefKlan == null) return GorusSonucu.HEDEF_YOK;
        Klan hedef = plugin.klanManager().klanBul(hedefKlan);
        if (hedef == null) return GorusSonucu.HEDEF_YOK;
        IliskiDurumu d = null;
        if (durum != null) {
            d = IliskiDurumu.oku(durum);
            if (d == null) return GorusSonucu.GECERSIZ_DURUM;
        }
        String ad = org.bukkit.Bukkit.getOfflinePlayer(yazanOyuncu).getName();
        IliskiManager.GorusIslemi r = plugin.iliski().gorusIsle(yazanOyuncu, ad == null ? yazanOyuncu.toString() : ad, hedef, d, not, null);
        return r.sonuc();
    }
}
