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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.elytrium.commons.config.Placeholders;
import net.elytrium.limboauth.handler.AuthSessionHandler;
import net.elytrium.limboauth.model.RegisteredPlayer;
import net.elytrium.limboauth.socialaddon.Addon;
import net.elytrium.limboauth.socialaddon.Settings;
import net.elytrium.limboauth.socialaddon.SocialManager;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.social.SocialMessageListenerAdapter;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;

public class MessageHandler implements SocialMessageListenerAdapter {

  private final Addon addon;
  private final SocialManager socialManager;
  private final Dao<SocialPlayer, String> dao;

  public MessageHandler(Addon addon, SocialManager socialManager, Dao<SocialPlayer, String> dao) {
    this.addon = addon;
    this.socialManager = socialManager;
    this.dao = dao;
  }

  @Override
  public void accept(String dbField, Long id, String message) throws SQLException {
    String lowercaseMessage = message.toLowerCase(Locale.ROOT);

    if (Settings.IMP.MAIN.START_MESSAGES.contains(lowercaseMessage)) {
      this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.START_REPLY);
      return;
    }

    // social-register-cmds
    for (String cmd : Settings.IMP.MAIN.SOCIAL_REGISTER_CMDS) {
      if (lowercaseMessage.startsWith(cmd)) {
        this.handleRegister(dbField, id, message, cmd);
        return;
      }
    }

    // step-1 code: player sent code A to bot
    try {
      int receivedCode = Integer.parseInt(lowercaseMessage.trim());
      if (this.handleCodeA(dbField, id, receivedCode)) {
        return;
      }
    } catch (NumberFormatException ignored) {
      // not a code
    }

    // social-link-cmds
    for (String cmd : Settings.IMP.MAIN.SOCIAL_LINK_CMDS) {
      if (lowercaseMessage.startsWith(cmd)) {
        this.handleLink(dbField, id, message, cmd);
        return;
      }
    }

    // force-keyboard-cmds
    for (String cmd : Settings.IMP.MAIN.FORCE_KEYBOARD_CMDS) {
      if (lowercaseMessage.startsWith(cmd)) {
        if (this.dao.queryBuilder().where().eq(dbField, id).countOf() == 0) {
          this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.START_REPLY);
        } else {
          this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.KEYBOARD_RESTORED, this.addon.getKeyboard());
        }
        return;
      }
    }
  }

  private void handleRegister(String dbField, Long id, String message, String cmd) throws SQLException {
    int desiredLength = cmd.length() + 1;
    if (message.length() <= desiredLength) {
      this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.LINK_SOCIAL_REGISTER_CMD_USAGE);
      return;
    }

    String userIndex = dbField + id;
    Addon.CachedRegisteredUser cached = this.addon.getCachedRegistration(userIndex);
    if (cached.getRegistrationAmount() >= Settings.IMP.MAIN.MAX_REGISTRATION_COUNT_PER_TIME) {
      this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.REGISTER_LIMIT);
      return;
    }
    cached.incrementRegistrationAmount();

    String account = message.substring(desiredLength);
    if (!this.addon.getNicknamePattern().matcher(account).matches()) {
      this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.REGISTER_INCORRECT_NICKNAME);
      return;
    }

    String lowercaseNickname = account.toLowerCase(Locale.ROOT);
    if (this.addon.getPlugin().getPlayerDao().idExists(lowercaseNickname)) {
      this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.REGISTER_TAKEN_NICKNAME);
      return;
    }

    if (!Settings.IMP.MAIN.ALLOW_PREMIUM_NAMES_REGISTRATION && this.addon.getPlugin().isPremium(lowercaseNickname)) {
      this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.REGISTER_PREMIUM_NICKNAME);
      return;
    }

    String newPassword = Long.toHexString(Double.doubleToLongBits(Math.random()));
    RegisteredPlayer player = new RegisteredPlayer(account, "", "").setPassword(newPassword);
    this.addon.getPlugin().getPlayerDao().create(player);
    this.addon.linkSocial(lowercaseNickname, dbField, id);
    this.socialManager.broadcastMessage(dbField, id, Placeholders.replace(Settings.IMP.MAIN.STRINGS.REGISTER_SUCCESS, newPassword));
  }

  private boolean handleCodeA(String dbField, Long id, int receivedCode) throws SQLException {
    for (Map.Entry<String, Integer> entry : this.addon.getLinkInitCodeMap().entrySet()) {
      String nickname = entry.getKey();
      if (entry.getValue() == receivedCode && dbField.equals(this.addon.getLinkInitFieldMap().get(nickname))) {

        if (this.dao.queryForEq(dbField, id).size() >= Settings.IMP.MAIN.MAX_ACCOUNTS_PER_SOCIAL) {
          this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.LINK_MAX_ACCOUNTS);
          this.addon.clearLinkInitMaps(nickname);
          return true;
        }

        int codeB = ThreadLocalRandom.current().nextInt(Settings.IMP.MAIN.CODE_LOWER_BOUND, Settings.IMP.MAIN.CODE_UPPER_BOUND);
        String socialName = this.addon.clearLinkInitMaps(nickname);
        if (socialName == null) socialName = dbField;

        this.addon.getConfirmCodeMap().put(nickname, codeB);
        this.addon.getRequestedReverseMap().put(nickname, new Addon.TempAccount(dbField, id));

        this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.LINK_BOT_ALMOST_DONE);

        String socialDisplayName = this.socialManager.getUserDisplayName(dbField, id);
        final String finalSocialName = socialName;
        final int finalCodeB = codeB;
        this.addon.getServer().getPlayer(nickname).ifPresent(p -> p.sendMessage(
            Addon.getSerializer().deserialize(
                Settings.IMP.MAIN.STRINGS.LINK_CONFIRM_GAME
                    .replace("{SOCIAL}", finalSocialName)
                    .replace("{SOCIAL_NAME}", socialDisplayName)
                    .replace("{CODE}", String.valueOf(finalCodeB)))));
        return true;
      }
    }
    return false;
  }

  private void handleLink(String dbField, Long id, String message, String cmd) throws SQLException {
    int desiredLength = cmd.length() + 1;
    if (message.length() <= desiredLength) {
      this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.LINK_SOCIAL_CMD_USAGE);
      return;
    }

    String[] args = message.substring(desiredLength).split(" ");
    String account = args[0].toLowerCase(Locale.ROOT);

    if (!this.addon.getNicknamePattern().matcher(account).matches()) {
      this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.LINK_UNKNOWN_ACCOUNT);
      return;
    }

    if (args.length == 1) {
      if (Settings.IMP.MAIN.DISABLE_LINK_WITHOUT_PASSWORD) {
        this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.LINK_SOCIAL_CMD_USAGE);
        return;
      }
      int code = ThreadLocalRandom.current().nextInt(Settings.IMP.MAIN.CODE_LOWER_BOUND, Settings.IMP.MAIN.CODE_UPPER_BOUND);
      this.addon.getCodeMap().put(account, code);
      this.addon.getRequestedReverseMap().put(account, new Addon.TempAccount(dbField, id));
      this.socialManager.broadcastMessage(dbField, id, Placeholders.replace(Settings.IMP.MAIN.STRINGS.LINK_CODE, String.valueOf(code)));
    } else {
      if (Settings.IMP.MAIN.DISABLE_LINK_WITH_PASSWORD) {
        this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.LINK_SOCIAL_CMD_USAGE);
        return;
      }
      RegisteredPlayer registeredPlayer = this.addon.getPlugin().getPlayerDao().queryForId(account);
      if (AuthSessionHandler.checkPassword(args[1], registeredPlayer, this.addon.getPlugin().getPlayerDao())) {
        this.addon.linkSocial(account, dbField, id);
        this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.LINK_SUCCESS);
      } else {
        this.socialManager.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.LINK_WRONG_PASSWORD);
      }
    }
  }
}
