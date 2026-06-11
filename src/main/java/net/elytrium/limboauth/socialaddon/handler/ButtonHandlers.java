/*
 * Copyright (C) 2022 - 2026 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package net.elytrium.limboauth.socialaddon.handler;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.elytrium.commons.config.Placeholders;
import net.elytrium.limboauth.LimboAuth;
import net.elytrium.limboauth.model.RegisteredPlayer;
import net.elytrium.limboauth.socialaddon.Addon;
import net.elytrium.limboauth.socialaddon.Settings;
import net.elytrium.limboauth.socialaddon.SocialManager;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.social.AbstractSocial;
import net.elytrium.limboauth.socialaddon.utils.GeoIp;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.stmt.UpdateBuilder;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;

public class ButtonHandlers {

  private final Addon addon;
  private final SocialManager socialManager;
  private final Dao<SocialPlayer, String> dao;
  private final LimboAuth plugin;
  private final GeoIp geoIp;

  public ButtonHandlers(Addon addon, SocialManager socialManager, Dao<SocialPlayer, String> dao,
                        LimboAuth plugin, GeoIp geoIp) {
    this.addon = addon;
    this.socialManager = socialManager;
    this.dao = dao;
    this.plugin = plugin;
    this.geoIp = geoIp;
  }

  public void registerAll() {
    this.registerInfoBtn();
    this.registerBlockBtn();
    this.registerTotpBtn();
    this.registerNotifyBtn();
    this.registerKickBtn();
    this.registerRestoreBtn();
    this.registerUnlinkBtn();
    this.registerUnlinkConfirmBtns();
  }

  private void registerInfoBtn() {
    this.socialManager.addButtonEvent("info", (dbField, id) -> {
      List<SocialPlayer> list = this.dao.queryForEq(dbField, id);
      if (list.isEmpty()) return;
      this.addon.withAccountSelection(dbField, id, list, player -> {
        Optional<Player> proxyPlayer = this.addon.getServer().getPlayer(player.getLowercaseNickname());
        String server, ip, location;
        if (proxyPlayer.isPresent()) {
          Player p = proxyPlayer.get();
          Optional<ServerConnection> conn = p.getCurrentServer();
          server = conn.map(c -> c.getServerInfo().getName()).orElse(Settings.IMP.MAIN.STRINGS.STATUS_OFFLINE);
          ip = p.getRemoteAddress().getAddress().getHostAddress();
          location = Optional.ofNullable(this.geoIp).map(g -> g.getLocation(ip)).orElse("");
        } else {
          server = Settings.IMP.MAIN.STRINGS.STATUS_OFFLINE;
          ip = Settings.IMP.MAIN.STRINGS.STATUS_OFFLINE;
          location = "";
        }
        this.socialManager.broadcastMessage(dbField, id, Placeholders.replace(Settings.IMP.MAIN.STRINGS.INFO_MSG,
            player.getLowercaseNickname(), server, ip, location,
            player.isNotifyEnabled() ? Settings.IMP.MAIN.STRINGS.NOTIFY_ENABLED : Settings.IMP.MAIN.STRINGS.NOTIFY_DISABLED,
            player.isBlocked() ? Settings.IMP.MAIN.STRINGS.BLOCK_ENABLED : Settings.IMP.MAIN.STRINGS.BLOCK_DISABLED,
            player.isTotpEnabled() ? Settings.IMP.MAIN.STRINGS.TOTP_ENABLED : Settings.IMP.MAIN.STRINGS.TOTP_DISABLED),
            this.addon.getKeyboard());
      });
    });
  }

  private void registerBlockBtn() {
    this.socialManager.addButtonEvent("block", (dbField, id) -> {
      List<SocialPlayer> list = this.dao.queryForEq(dbField, id);
      if (list.isEmpty()) return;
      this.addon.withAccountSelection(dbField, id, list, player -> {
        try {
          if (player.isBlocked()) {
            player.setBlocked(false);
            this.socialManager.broadcastMessage(dbField, id,
                Placeholders.replace(Settings.IMP.MAIN.STRINGS.UNBLOCK_SUCCESS, player.getLowercaseNickname()), this.addon.getKeyboard());
          } else {
            player.setBlocked(true);
            this.plugin.removePlayerFromCache(player.getLowercaseNickname());
            this.addon.getServer().getPlayer(player.getLowercaseNickname())
                .ifPresent(p -> p.disconnect(Addon.getSerializer().deserialize(Settings.IMP.MAIN.STRINGS.KICK_GAME_MESSAGE)));
            this.socialManager.broadcastMessage(dbField, id,
                Placeholders.replace(Settings.IMP.MAIN.STRINGS.BLOCK_SUCCESS, player.getLowercaseNickname()), this.addon.getKeyboard());
          }
          this.dao.update(player);
        } catch (SQLException e) { throw new IllegalStateException(e); }
      });
    });
  }

  private void registerTotpBtn() {
    this.socialManager.addButtonEvent("2fa", (dbField, id) -> {
      List<SocialPlayer> list = this.dao.queryForEq(dbField, id);
      if (list.isEmpty()) return;
      this.addon.withAccountSelection(dbField, id, list, player -> {
        try {
          if (player.isTotpEnabled()) {
            player.setTotpEnabled(false);
            this.socialManager.broadcastMessage(dbField, id,
                Placeholders.replace(Settings.IMP.MAIN.STRINGS.TOTP_DISABLE_SUCCESS, player.getLowercaseNickname()), this.addon.getKeyboard());
          } else {
            player.setTotpEnabled(true);
            this.socialManager.broadcastMessage(dbField, id,
                Placeholders.replace(Settings.IMP.MAIN.STRINGS.TOTP_ENABLE_SUCCESS, player.getLowercaseNickname()), this.addon.getKeyboard());
          }
          this.dao.update(player);
        } catch (SQLException e) { throw new IllegalStateException(e); }
      });
    });
  }

  private void registerNotifyBtn() {
    this.socialManager.addButtonEvent("notify", (dbField, id) -> {
      List<SocialPlayer> list = this.dao.queryForEq(dbField, id);
      if (list.isEmpty()) return;
      this.addon.withAccountSelection(dbField, id, list, player -> {
        try {
          if (player.isNotifyEnabled()) {
            player.setNotifyEnabled(false);
            this.socialManager.broadcastMessage(dbField, id,
                Placeholders.replace(Settings.IMP.MAIN.STRINGS.NOTIFY_DISABLE_SUCCESS, player.getLowercaseNickname()), this.addon.getKeyboard());
          } else {
            player.setNotifyEnabled(true);
            this.socialManager.broadcastMessage(dbField, id,
                Placeholders.replace(Settings.IMP.MAIN.STRINGS.NOTIFY_ENABLE_SUCCESS, player.getLowercaseNickname()), this.addon.getKeyboard());
          }
          this.dao.update(player);
        } catch (SQLException e) { throw new IllegalStateException(e); }
      });
    });
  }

  private void registerKickBtn() {
    this.socialManager.addButtonEvent("kick", (dbField, id) -> {
      List<SocialPlayer> list = this.dao.queryForEq(dbField, id);
      if (list.isEmpty()) return;
      this.addon.withAccountSelection(dbField, id, list, player -> {
        try {
          Optional<Player> proxyPlayer = this.addon.getServer().getPlayer(player.getLowercaseNickname());
          this.plugin.removePlayerFromCache(player.getLowercaseNickname());
          if (proxyPlayer.isPresent()) {
            proxyPlayer.get().disconnect(Addon.getSerializer().deserialize(Settings.IMP.MAIN.STRINGS.KICK_GAME_MESSAGE));
            this.socialManager.broadcastMessage(dbField, id,
                Placeholders.replace(Settings.IMP.MAIN.STRINGS.KICK_SUCCESS, player.getLowercaseNickname()), this.addon.getKeyboard());
          } else {
            this.socialManager.broadcastMessage(dbField, id,
                Settings.IMP.MAIN.STRINGS.KICK_IS_OFFLINE.replace("{NICKNAME}", player.getLowercaseNickname()), this.addon.getKeyboard());
          }
          this.dao.update(player);
        } catch (SQLException e) { throw new IllegalStateException(e); }
      });
    });
  }

  private void registerRestoreBtn() {
    this.socialManager.addButtonEvent("restore", (dbField, id) -> {
      List<SocialPlayer> list = this.dao.queryForEq(dbField, id);
      if (list.isEmpty()) return;
      this.addon.withAccountSelection(dbField, id, list, player -> {
        try {
          if (Settings.IMP.MAIN.PROHIBIT_PREMIUM_RESTORE
              && this.plugin.isPremiumInternal(player.getLowercaseNickname()).getState() != LimboAuth.PremiumState.CRACKED) {
            this.socialManager.broadcastMessage(dbField, id,
                Placeholders.replace(Settings.IMP.MAIN.STRINGS.RESTORE_MSG_PREMIUM, player.getLowercaseNickname()), this.addon.getKeyboard());
            return;
          }
          Dao<RegisteredPlayer, String> playerDao = this.plugin.getPlayerDao();
          String newPassword = Long.toHexString(Double.doubleToLongBits(Math.random()));
          UpdateBuilder<RegisteredPlayer, String> ub = playerDao.updateBuilder();
          ub.where().eq(RegisteredPlayer.LOWERCASE_NICKNAME_FIELD, player.getLowercaseNickname());
          ub.updateColumnValue(RegisteredPlayer.HASH_FIELD, RegisteredPlayer.genHash(newPassword));
          if (ub.update() != 0) {
            this.socialManager.broadcastMessage(dbField, id,
                Placeholders.replace(Settings.IMP.MAIN.STRINGS.RESTORE_MSG, player.getLowercaseNickname(), newPassword), this.addon.getKeyboard());
          } else {
            this.socialManager.broadcastMessage(dbField, id,
                Placeholders.replace(Settings.IMP.MAIN.STRINGS.RESTORE_MSG_PREMIUM, player.getLowercaseNickname()), this.addon.getKeyboard());
          }
        } catch (SQLException e) { throw new IllegalStateException(e); }
      });
    });
  }

  private void registerUnlinkBtn() {
    this.socialManager.addButtonEvent("unlink", (dbField, id) -> {
      if (Settings.IMP.MAIN.DISABLE_UNLINK) {
        this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.UNLINK_DISABLED, this.addon.getKeyboard());
        return;
      }
      List<SocialPlayer> list = this.dao.queryForEq(dbField, id);
      if (list.isEmpty()) return;
      this.addon.withAccountSelection(dbField, id, list, player -> {
        if (player.isBlocked()) {
          this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.UNLINK_BLOCK_CONFLICT, this.addon.getKeyboard());
          return;
        }
        if (player.isTotpEnabled()) {
          this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.UNLINK_2FA_CONFLICT, this.addon.getKeyboard());
          return;
        }
        this.addon.getPendingUnlinks().put(dbField + id, player);
        List<List<AbstractSocial.ButtonItem>> confirmBtns = List.of(List.of(
            new AbstractSocial.ButtonItem("unlink_yes", Settings.IMP.MAIN.STRINGS.UNLINK_CONFIRM_YES, AbstractSocial.ButtonItem.Color.RED),
            new AbstractSocial.ButtonItem("unlink_no", Settings.IMP.MAIN.STRINGS.UNLINK_CONFIRM_NO, AbstractSocial.ButtonItem.Color.GREEN)
        ));
        this.socialManager.broadcastMessage(dbField, id,
            Settings.IMP.MAIN.STRINGS.UNLINK_CONFIRM_MSG.replace("{NICKNAME}", player.getLowercaseNickname()),
            confirmBtns, AbstractSocial.ButtonVisibility.PREFER_INLINE);
      });
    });
  }

  private void registerUnlinkConfirmBtns() {
    this.socialManager.removeButtonEvent("unlink_yes");
    this.socialManager.removeButtonEvent("unlink_no");

    this.socialManager.addButtonEvent("unlink_yes", (dbField, id) -> {
      SocialPlayer player = this.addon.getPendingUnlinks().remove(dbField + id);
      if (player == null) return;
      try {
        if (player.isBlocked()) {
          this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.UNLINK_BLOCK_CONFLICT, this.addon.getKeyboard());
          return;
        }
        if (player.isTotpEnabled()) {
          this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.UNLINK_2FA_CONFLICT, this.addon.getKeyboard());
          return;
        }
        SocialPlayer.DatabaseField.valueOf(dbField).setIdFor(player, null);
        boolean allUnlinked = Arrays.stream(SocialPlayer.DatabaseField.values()).noneMatch(v -> v.getIdFor(player) != null);
        if (Settings.IMP.MAIN.UNLINK_BTN_ALL || allUnlinked) {
          this.dao.delete(player);
          this.socialManager.unregisterHook(player);
          Settings.IMP.MAIN.AFTER_UNLINKAGE_COMMANDS.forEach(cmd ->
              this.addon.getServer().getCommandManager().executeAsync(p -> Tristate.TRUE, cmd.replace("{NICKNAME}", player.getLowercaseNickname())));
        } else {
          UpdateBuilder<SocialPlayer, String> ub = this.dao.updateBuilder();
          ub.where().eq(SocialPlayer.LOWERCASE_NICKNAME_FIELD, player.getLowercaseNickname());
          ub.updateColumnValue(dbField, null);
          ub.update();
          this.socialManager.unregisterHook(dbField, player);
        }
        this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.UNLINK_SUCCESS, this.addon.getKeyboard());
        this.addon.getServer().getPlayer(player.getLowercaseNickname()).ifPresent(p ->
            p.sendMessage(Addon.getSerializer().deserialize(Settings.IMP.MAIN.STRINGS.UNLINK_SUCCESS_GAME)));
      } catch (SQLException e) { throw new IllegalStateException(e); }
    });

    this.socialManager.addButtonEvent("unlink_no", (dbField, id) -> {
      this.addon.getPendingUnlinks().remove(dbField + id);
      this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.UNLINK_CANCELLED, this.addon.getKeyboard());
    });
  }
}
