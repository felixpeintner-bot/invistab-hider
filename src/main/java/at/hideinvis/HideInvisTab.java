package at.hideinvis;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Unsichtbare Spieler (Trank, /effect) verschwinden aus der Tab-Liste aller ANDEREN Spieler.
 * Damit sind Name und Skin-Kopf in der Liste weg. Endet die Unsichtbarkeit, kommt der Eintrag zurück.
 * Der Spieler selbst sieht sich weiter in seiner eigenen Liste.
 */
public class HideInvisTab implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("hideinvistab");

	/** Spieler, die den anderen gerade aus der Tab-Liste entfernt wurden. */
	private final Set<UUID> hidden = new HashSet<>();

	private String textureValue = "";
	private String textureSignature = "";
	private String fakeName = "???";

	private void loadConfig() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("hideinvistab.json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		try {
			if (!Files.exists(file)) {
				JsonObject o = new JsonObject();
				o.addProperty("texture_value", "");
				o.addProperty("texture_signature", "");
				o.addProperty("fake_name", "???");
				Files.writeString(file, gson.toJson(o));
			}
			JsonObject o = gson.fromJson(Files.readString(file), JsonObject.class);
			this.textureValue = o.has("texture_value") ? o.get("texture_value").getAsString().trim() : "";
			this.textureSignature = o.has("texture_signature") ? o.get("texture_signature").getAsString().trim() : "";
			this.fakeName = o.has("fake_name") ? o.get("fake_name").getAsString() : "???";
		} catch (Exception e) {
			LOGGER.warn("Konfig konnte nicht gelesen werden, benutze Entfernen statt Tarnung", e);
		}
	}

	private boolean disguiseConfigured() {
		return !this.textureValue.isEmpty() && !this.textureSignature.isEmpty();
	}

	/** Baut ein Tab-Paket mit falschem Namen und schwarzem Skin. Gibt null zurück, wenn etwas schiefgeht. */
	private net.minecraft.network.protocol.Packet<?> disguisePacket(ServerPlayer player) {
		if (!disguiseConfigured()) {
			return null;
		}
		try {
			ClientboundPlayerInfoUpdatePacket packet =
					ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
			List<ClientboundPlayerInfoUpdatePacket.Entry> fakeEntries = new ArrayList<>();
			for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
				fakeEntries.add(withProfile(entry, fakeProfile(player.getUUID())));
			}
			Field f = ClientboundPlayerInfoUpdatePacket.class.getDeclaredField("entries");
			f.setAccessible(true);
			f.set(packet, fakeEntries);
			return packet;
		} catch (Throwable t) {
			LOGGER.warn("Tarn-Paket fehlgeschlagen, benutze Entfernen", t);
			return null;
		}
	}

	private GameProfile fakeProfile(UUID id) throws Exception {
		Property tex = new Property("textures", this.textureValue, this.textureSignature);
		Object props = null;
		Class<?> pmClass = Class.forName("com.mojang.authlib.properties.PropertyMap");
		for (Constructor<?> c : pmClass.getConstructors()) {
			if (c.getParameterCount() == 1) {
				props = c.newInstance(ImmutableMultimap.of("textures", tex));
				break;
			}
		}
		for (Constructor<?> c : GameProfile.class.getConstructors()) {
			if (c.getParameterCount() == 3 && props != null) {
				return (GameProfile) c.newInstance(id, this.fakeName, props);
			}
		}
		throw new IllegalStateException("GameProfile-Konstruktor nicht gefunden");
	}

	private static ClientboundPlayerInfoUpdatePacket.Entry withProfile(
			ClientboundPlayerInfoUpdatePacket.Entry entry, GameProfile profile) throws Exception {
		RecordComponent[] comps = ClientboundPlayerInfoUpdatePacket.Entry.class.getRecordComponents();
		Class<?>[] types = new Class<?>[comps.length];
		Object[] values = new Object[comps.length];
		for (int i = 0; i < comps.length; i++) {
			types[i] = comps[i].getType();
			Object v = comps[i].getAccessor().invoke(entry);
			values[i] = types[i] == GameProfile.class ? profile : v;
		}
		Constructor<ClientboundPlayerInfoUpdatePacket.Entry> c =
				ClientboundPlayerInfoUpdatePacket.Entry.class.getDeclaredConstructor(types);
		c.setAccessible(true);
		return c.newInstance(values);
	}

	/** Versteckt den Spieler vor einem Empfänger: erst Eintrag entfernen, dann Tarn-Eintrag (falls möglich). */
	private void hideFor(ServerPlayer receiver, ServerPlayer player) {
		receiver.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
		net.minecraft.network.protocol.Packet<?> fake = disguisePacket(player);
		if (fake != null) {
			receiver.connection.send(fake);
		}
	}

	private void showFor(ServerPlayer receiver, ServerPlayer player) {
		receiver.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
		receiver.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
	}

	@Override
	public void onInitialize() {
		loadConfig();
		// Zweimal pro Sekunde prüfen, ob sich der Unsichtbarkeits-Zustand geändert hat.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 10 == 0) {
				update(server);
			}
		});

		// Wer neu beitritt, bekommt die schon versteckten Spieler gar nicht erst in der Liste.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!this.hidden.isEmpty()) {
				ServerPlayer joiner = handler.getPlayer();
				joiner.connection.send(new ClientboundPlayerInfoRemovePacket(new ArrayList<>(this.hidden)));
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				this.hidden.remove(handler.getPlayer().getUUID()));

		LOGGER.info("Hide Invis Tab geladen");
	}

	private void update(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID id = player.getUUID();
			boolean shouldHide = player.isInvisible() && !player.isSpectator();
			boolean isHidden = this.hidden.contains(id);

			if (shouldHide && !isHidden) {
				this.hidden.add(id);
				for (ServerPlayer other : server.getPlayerList().getPlayers()) {
						if (other != player) {
							hideFor(other, player);
						}
					}
			} else if (!shouldHide && isHidden) {
				this.hidden.remove(id);
				for (ServerPlayer other : server.getPlayerList().getPlayers()) {
						if (other != player) {
							showFor(other, player);
						}
					}
			}
		}
	}
}
