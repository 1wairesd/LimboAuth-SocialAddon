/*
 * Copyright (C) 2022 - 2026 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limboauth.socialaddon;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.elytrium.commons.kyori.serialization.Serializer;
import net.elytrium.commons.kyori.serialization.Serializers;
import net.elytrium.commons.utils.updates.UpdatesChecker;
import net.elytrium.limboauth.LimboAuth;
import net.elytrium.limboauth.socialaddon.command.ForceSocialUnlinkCommand;
import net.elytrium.limboauth.socialaddon.command.ValidateLinkCommand;
import net.elytrium.limboauth.socialaddon.handler.ButtonHandlers;
import net.elytrium.limboauth.socialaddon.handler.MessageHandler;
import net.elytrium.limboauth.socialaddon.listener.LimboAuthListener;
import net.elytrium.limboauth.socialaddon.listener.ReloadListener;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.social.AbstractSocial;
import net.elytrium.limboauth.socialaddon.social.DiscordSocial;
import net.elytrium.limboauth.socialaddon.social.TelegramSocial;
import net.elytrium.limboauth.socialaddon.social.VKSocial;
import net.elytrium.limboauth.socialaddon.utils.GeoIp;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.DaoManager;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.stmt.UpdateBuilder;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.support.ConnectionSource;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.table.TableUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ComponentSerializer;
import org.bstats.velocity.Metrics;
import org.slf4j.Logger;

@Plugin(
    id = "limboauth-social-addon",
    name = "LimboAuth Social Addon",
    version = BuildConstants.ADDON_VERSION,
    url = "https://elytrium.net/",
    authors = {"Elytrium (https://elytrium.net/)"},
    dependencies = {@Dependency(id = "limboauth")}
)
public class Addon {

  private static final String SELECT_BTN_PREFIX = "sel_";
  private static final String PLUGIN_MINIMUM_VERSION = "1.1.0";
  private static Serializer SERIALIZER;

  private final ProxyServer server;
  private final Logger logger;
  private final Metrics.Factory metricsFactory;
  private final Path dataDirectory;
  private final LimboAuth plugin;

  // Legacy link flow (bot → game)
  private final Map<String, Integer> codeMap = new ConcurrentHashMap<>();
  private final Map<String, TempAccount> requestedReverseMap = new ConcurrentHashMap<>();
  private final Map<String, CachedRegisteredUser> cachedAccountRegistrations = new ConcurrentHashMap<>();

  // New link flow (game → bot → game)
  private final Map<String, Integer> linkInitCodeMap = new ConcurrentHashMap<>();
  private final Map<String, String> linkInitFieldMap = new ConcurrentHashMap<>();
  private final Map<String, String> linkInitSocialNameMap = new ConcurrentHashMap<>();
  private final Map<String, Integer> confirmCodeMap = new ConcurrentHashMap<>();

  // Unlink confirmation
  private final Map<String, SocialPlayer> pendingUnlinks = new ConcurrentHashMap<>();

  // Multi-account selection
  private final Map<String, PendingAction> pendingActions = new ConcurrentHashMap<>();

  private Dao<SocialPlayer, String> dao;
  private Pattern nicknamePattern;
  private SocialManager socialManager;
  private List<List<AbstractSocial.ButtonItem>> keyboard;
  private GeoIp geoIp;
  private net.elytrium.limboauth.socialaddon.social.AbstractSocial.ButtonVisibility defaultVisibility;

  static {
    Objects.requireNonNull(org.apache.commons.logging.impl.LogFactoryImpl.class);
    Objects.requireNonNull(org.apache.commons.logging.impl.Log4JLogger.class);
    Objects.requireNonNull(org.apache.commons.logging.impl.Jdk14Logger.class);
    Objects.requireNonNull(org.apache.commons.logging.impl.Jdk13LumberjackLogger.class);
    Objects.requireNonNull(org.apache.commons.logging.impl.SimpleLog.class);
  }

  @Inject
  public Addon(ProxyServer server, Logger logger, Metrics.Factory metricsFactory, @DataDirectory Path dataDirectory) {
    this.server = server;
    this.logger = logger;
    this.metricsFactory = metricsFactory;
    this.dataDirectory = dataDirectory;

    Optional<PluginContainer> container = this.server.getPluginManager().getPlugin("limboauth");
    String version = container.map(PluginContainer::getDescription).flatMap(PluginDescription::getVersion).orElseThrow();

    if (!UpdatesChecker.checkVersion(PLUGIN_MINIMUM_VERSION, version)) {
      throw new IllegalStateException("Incorrect version of LimboAuth plugin, the addon requires version " + PLUGIN_MINIMUM_VERSION + " or newer");
    }

    this.plugin = (LimboAuth) container.flatMap(PluginContainer::getInstance).orElseThrow();
  }

  @Subscribe(order = PostOrder.NORMAL)
  public void onProxyInitialization(ProxyInitializeEvent event) throws SQLException {
    this.onReload();
    this.metricsFactory.make(this, 14770);
    if (!UpdatesChecker.checkVersionByURL("https://raw.githubusercontent.com/Elytrium/LimboAuth-SocialAddon/master/VERSION", Settings.IMP.VERSION)) {
      this.logger.error("****************************************");
      this.logger.warn("The new LimboAuth update was found, please update.");
      this.logger.error("https://github.com/Elytrium/LimboAuth-SocialAddon/releases/");
      this.logger.error("****************************************");
    }
  }

  @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH", justification = "LEGACY_AMPERSAND can't be null in velocity.")
  private void load() {
    Settings.IMP.reload(new File(this.dataDirectory.toFile().getAbsoluteFile(), "config.yml"), Settings.IMP.PREFIX);

    ComponentSerializer<Component, Component, String> serializer = Settings.IMP.SERIALIZER.getSerializer();
    if (serializer == null) {
      this.logger.warn("The specified serializer could not be founded, using default. (LEGACY_AMPERSAND)");
      setSerializer(new Serializer(Objects.requireNonNull(Serializers.LEGACY_AMPERSAND.getSerializer())));
    } else {
      setSerializer(new Serializer(serializer));
    }

    this.geoIp = Settings.IMP.MAIN.GEOIP.ENABLED ? new GeoIp(this.dataDirectory) : null;

    if (this.socialManager != null) {
      this.socialManager.stop();
    }

    this.socialManager = new SocialManager(DiscordSocial::new, TelegramSocial::new, VKSocial::new);
    this.socialManager.start();

    this.keyboard = List.of(
        List.of(new AbstractSocial.ButtonItem("info", Settings.IMP.MAIN.STRINGS.INFO_BTN, AbstractSocial.ButtonItem.Color.PRIMARY)),
        List.of(
            new AbstractSocial.ButtonItem("block", Settings.IMP.MAIN.STRINGS.BLOCK_TOGGLE_BTN, AbstractSocial.ButtonItem.Color.SECONDARY),
            new AbstractSocial.ButtonItem("2fa", Settings.IMP.MAIN.STRINGS.TOGGLE_2FA_BTN, AbstractSocial.ButtonItem.Color.SECONDARY)
        ),
        List.of(new AbstractSocial.ButtonItem("notify", Settings.IMP.MAIN.STRINGS.TOGGLE_NOTIFICATION_BTN, AbstractSocial.ButtonItem.Color.SECONDARY)),
        List.of(
            new AbstractSocial.ButtonItem("kick", Settings.IMP.MAIN.STRINGS.KICK_BTN, AbstractSocial.ButtonItem.Color.RED),
            new AbstractSocial.ButtonItem("restore", Settings.IMP.MAIN.STRINGS.RESTORE_BTN, AbstractSocial.ButtonItem.Color.RED),
            new AbstractSocial.ButtonItem("unlink", Settings.IMP.MAIN.STRINGS.UNLINK_BTN, AbstractSocial.ButtonItem.Color.RED)
        )
    );
    this.socialManager.registerKeyboard(this.keyboard);

    // Register unlink confirm buttons for value→id mapping
    this.socialManager.registerKeyboard(List.of(List.of(
        new AbstractSocial.ButtonItem("unlink_yes", Settings.IMP.MAIN.STRINGS.UNLINK_CONFIRM_YES, AbstractSocial.ButtonItem.Color.RED),
        new AbstractSocial.ButtonItem("unlink_no", Settings.IMP.MAIN.STRINGS.UNLINK_CONFIRM_NO, AbstractSocial.ButtonItem.Color.GREEN)
    )));

    // Register sel_0..sel_9 for account selection
    for (int i = 0; i < 10; i++) {
      final int index = i;
      String btnId = SELECT_BTN_PREFIX + i;
      this.socialManager.registerButton(new AbstractSocial.ButtonItem(btnId, btnId, AbstractSocial.ButtonItem.Color.PRIMARY));
      this.socialManager.removeButtonEvent(btnId);
      this.socialManager.addButtonEvent(btnId, (dbField, id) -> {
        PendingAction pending = this.pendingActions.remove(dbField + id);
        if (pending == null) return;
        List<SocialPlayer> accounts = pending.getAccounts();
        if (index < accounts.size()) {
          pending.getAction().accept(accounts.get(index));
        }
      });
    }

    // Register button handlers
    new ButtonHandlers(this, this.socialManager, this.dao, this.plugin, this.geoIp).registerAll();

    // Register message handler
    this.socialManager.addMessageEvent(new MessageHandler(this, this.socialManager, this.dao));
  }

  public void onReload() throws SQLException {
    this.server.getEventManager().unregisterListeners(this);

    ConnectionSource source = this.plugin.getConnectionSource();
    TableUtils.createTableIfNotExists(source, SocialPlayer.class);
    this.dao = DaoManager.createDao(source, SocialPlayer.class);
    this.plugin.migrateDb(this.dao);
    this.nicknamePattern = Pattern.compile(net.elytrium.limboauth.Settings.IMP.MAIN.ALLOWED_NICKNAME_REGEX);

    // load() must be called AFTER dao is initialized
    this.load();

    this.server.getEventManager().register(this, new LimboAuthListener(this, this.plugin, this.dao, this.socialManager, this.keyboard, this.geoIp));
    this.server.getEventManager().register(this, new ReloadListener(this));

    CommandManager commandManager = this.server.getCommandManager();
    commandManager.unregister(Settings.IMP.MAIN.LINKAGE_MAIN_CMD);
    commandManager.unregister(Settings.IMP.MAIN.FORCE_UNLINK_MAIN_CMD);
    commandManager.register(Settings.IMP.MAIN.LINKAGE_MAIN_CMD, new ValidateLinkCommand(this), Settings.IMP.MAIN.LINKAGE_ALIAS_CMD.toArray(new String[0]));
    commandManager.register(Settings.IMP.MAIN.FORCE_UNLINK_MAIN_CMD, new ForceSocialUnlinkCommand(this), Settings.IMP.MAIN.FORCE_UNLINK_ALIAS_CMD.toArray(new String[0]));
  }

  // ── Public API ────────────────────────────────────────────────────────────

  public void linkSocial(String lowercaseNickname, String dbField, Long id) throws SQLException {
    SocialPlayer socialPlayer = this.dao.queryForId(lowercaseNickname);
    if (socialPlayer == null) {
      Settings.IMP.MAIN.AFTER_LINKAGE_COMMANDS.forEach(cmd ->
          this.server.getCommandManager().executeAsync(p -> Tristate.TRUE, cmd.replace("{NICKNAME}", lowercaseNickname)));
      this.dao.create(new SocialPlayer(lowercaseNickname));
    } else if (!Settings.IMP.MAIN.ALLOW_ACCOUNT_RELINK && SocialPlayer.DatabaseField.valueOf(dbField).getIdFor(socialPlayer) != null) {
      this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.LINK_ALREADY);
      return;
    }
    UpdateBuilder<SocialPlayer, String> ub = this.dao.updateBuilder();
    ub.where().eq(SocialPlayer.LOWERCASE_NICKNAME_FIELD, lowercaseNickname);
    ub.updateColumnValue(dbField, id);
    ub.update();
  }

  public void unregisterPlayer(String nickname) {
    try {
      SocialPlayer player = this.dao.queryForId(nickname.toLowerCase(Locale.ROOT));
      if (player != null) {
        this.socialManager.unregisterHook(player);
        this.dao.delete(player);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  public boolean isAlreadyLinked(String lowercaseNickname, String dbField) throws SQLException {
    SocialPlayer player = this.dao.queryForId(lowercaseNickname);
    return player != null && SocialPlayer.DatabaseField.valueOf(dbField).getIdFor(player) != null;
  }

  public int generateLinkInitCode(String lowercaseNickname, String dbField, String socialName) {
    int code = ThreadLocalRandom.current().nextInt(Settings.IMP.MAIN.CODE_LOWER_BOUND, Settings.IMP.MAIN.CODE_UPPER_BOUND);
    this.linkInitCodeMap.put(lowercaseNickname, code);
    this.linkInitFieldMap.put(lowercaseNickname, dbField);
    this.linkInitSocialNameMap.put(lowercaseNickname, socialName);
    return code;
  }

  /** Removes step-1 maps for nickname, returns the social name */
  public String clearLinkInitMaps(String nickname) {
    this.linkInitCodeMap.remove(nickname);
    this.linkInitFieldMap.remove(nickname);
    return this.linkInitSocialNameMap.remove(nickname);
  }

  public Integer getCode(String nickname) { return this.codeMap.get(nickname); }
  public void removeCode(String nickname) { this.codeMap.remove(nickname); this.requestedReverseMap.remove(nickname); }
  public Integer getConfirmCode(String nickname) { return this.confirmCodeMap.get(nickname); }
  public void removeConfirmCode(String nickname) { this.confirmCodeMap.remove(nickname); this.requestedReverseMap.remove(nickname); }
  public TempAccount getTempAccount(String nickname) { return this.requestedReverseMap.get(nickname); }

  public void withAccountSelection(String dbField, Long id, List<SocialPlayer> accounts, Consumer<SocialPlayer> action) {
    if (accounts.size() == 1) {
      action.accept(accounts.get(0));
      return;
    }
    List<List<AbstractSocial.ButtonItem>> selectionButtons = new ArrayList<>();
    int limit = Math.min(accounts.size(), 10);
    for (int i = 0; i < limit; i++) {
      String nickname = accounts.get(i).getLowercaseNickname();
      String btnId = SELECT_BTN_PREFIX + i;
      selectionButtons.add(List.of(new AbstractSocial.ButtonItem(btnId, nickname, AbstractSocial.ButtonItem.Color.PRIMARY)));
      this.socialManager.registerButton(new AbstractSocial.ButtonItem(btnId, nickname, AbstractSocial.ButtonItem.Color.PRIMARY));
    }
    this.pendingActions.put(dbField + id, new PendingAction(accounts, action));
    this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.SELECT_ACCOUNT_MSG, selectionButtons, AbstractSocial.ButtonVisibility.PREFER_INLINE);
  }

  public CachedRegisteredUser getCachedRegistration(String userIndex) {
    return this.cachedAccountRegistrations.computeIfAbsent(userIndex, k -> new CachedRegisteredUser());
  }

  // ── Getters ───────────────────────────────────────────────────────────────

  public SocialManager getSocialManager() { return this.socialManager; }
  public ProxyServer getServer() { return this.server; }
  public LimboAuth getPlugin() { return this.plugin; }
  public List<List<AbstractSocial.ButtonItem>> getKeyboard() { return this.keyboard; }
  public Pattern getNicknamePattern() { return this.nicknamePattern; }
  public Map<String, Integer> getCodeMap() { return this.codeMap; }
  public Map<String, TempAccount> getRequestedReverseMap() { return this.requestedReverseMap; }
  public Map<String, Integer> getLinkInitCodeMap() { return this.linkInitCodeMap; }
  public Map<String, String> getLinkInitFieldMap() { return this.linkInitFieldMap; }
  public Map<String, Integer> getConfirmCodeMap() { return this.confirmCodeMap; }
  public Map<String, SocialPlayer> getPendingUnlinks() { return this.pendingUnlinks; }

  public static Serializer getSerializer() { return SERIALIZER; }
  private static void setSerializer(Serializer serializer) { SERIALIZER = serializer; }

  // ── Inner classes ─────────────────────────────────────────────────────────

  public static class TempAccount {
    private final String dbField;
    private final long id;

    public TempAccount(String dbField, long id) {
      this.dbField = dbField;
      this.id = id;
    }

    public String getDbField() { return this.dbField; }
    public long getId() { return this.id; }
  }

  public static class CachedRegisteredUser {
    private final long checkTime = System.currentTimeMillis();
    private int registrationAmount;

    public long getCheckTime() { return this.checkTime; }
    public int getRegistrationAmount() { return this.registrationAmount; }
    public void incrementRegistrationAmount() { this.registrationAmount++; }
  }

  public static final class PendingAction {
    private final List<SocialPlayer> accounts;
    private final Consumer<SocialPlayer> action;

    public PendingAction(List<SocialPlayer> accounts, Consumer<SocialPlayer> action) {
      this.accounts = accounts;
      this.action = action;
    }

    public List<SocialPlayer> getAccounts() { return this.accounts; }
    public Consumer<SocialPlayer> getAction() { return this.action; }
  }
}
