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

package net.elytrium.limboauth.socialaddon.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import net.elytrium.limboauth.socialaddon.Addon;
import net.elytrium.limboauth.socialaddon.Settings;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

public class ValidateLinkCommand implements SimpleCommand {

  private final Addon addon;

  public ValidateLinkCommand(Addon addon) {
    this.addon = addon;
  }

  @Override
  public void execute(Invocation invocation) {
    CommandSource source = invocation.source();
    if (!(source instanceof Player)) {
      return;
    }

    Player player = (Player) source;
    String[] args = invocation.arguments();
    String username = player.getUsername().toLowerCase(Locale.ROOT);

    if (args.length == 0) {
      player.sendMessage(Addon.getSerializer().deserialize(
          Settings.IMP.MAIN.STRINGS.LINK_AVAILABLE_OPTIONS));
      return;
    }

    String arg = args[0].toLowerCase(Locale.ROOT);

    if (arg.equals("tg") || arg.equals("ds")) {
      String dbField = arg.equals("tg") ? SocialPlayer.TELEGRAM_DB_FIELD : SocialPlayer.DISCORD_DB_FIELD;
      String socialName = arg.equals("tg") ? "Telegram" : "Discord";
      String botName = arg.equals("tg") ? Settings.IMP.MAIN.TELEGRAM_BOT_NAME : Settings.IMP.MAIN.DISCORD_BOT_NAME;

      try {
        if (this.addon.isAlreadyLinked(username, dbField)) {
          player.sendMessage(Addon.getSerializer().deserialize(Settings.IMP.MAIN.STRINGS.LINK_ALREADY));
          return;
        }
      } catch (SQLException ex) {
        throw new IllegalStateException(ex);
      }

      int codeA = this.addon.generateLinkInitCode(username, dbField, socialName);
      String codeStr = String.valueOf(codeA);

      String instructionRaw = Settings.IMP.MAIN.STRINGS.LINK_INSTRUCTION
          .replace("{BOT}", botName)
          .replace("{SOCIAL}", socialName);

      String hoverText = Settings.IMP.MAIN.STRINGS.LINK_CODE_HOVER.replace("{CODE}", codeStr);

      Component codeLinkComponent = Addon.getSerializer()
          .deserialize(Settings.IMP.MAIN.STRINGS.LINK_CODE_LINK_TEXT)
          .hoverEvent(HoverEvent.showText(Addon.getSerializer().deserialize(hoverText)))
          .clickEvent(ClickEvent.copyToClipboard(codeStr));

      String[] parts = instructionRaw.split("\\{CODE_LINK\\}", -1);
      Component message = Addon.getSerializer().deserialize(parts[0]);
      for (int i = 1; i < parts.length; i++) {
        message = message.append(codeLinkComponent).append(Addon.getSerializer().deserialize(parts[i]));
      }

      player.sendMessage(message);
      return;
    }

    try {
      int enteredCode = Integer.parseInt(arg);
      String username2 = player.getUsername().toLowerCase(Locale.ROOT);
      Integer validCode = this.addon.getConfirmCode(username2);

      if (validCode != null && validCode == enteredCode) {
        Addon.TempAccount tempAccount = this.addon.getTempAccount(username2);
        this.addon.linkSocial(username2, tempAccount.getDbField(), tempAccount.getId());
        this.addon.getSocialManager().registerHook(tempAccount.getDbField(), tempAccount.getId());
        this.addon.getSocialManager().broadcastMessage(
            tempAccount.getDbField(), tempAccount.getId(),
            Settings.IMP.MAIN.STRINGS.LINK_SUCCESS, this.addon.getKeyboard());
        player.sendMessage(Addon.getSerializer().deserialize(Settings.IMP.MAIN.STRINGS.LINK_SUCCESS_GAME));
        this.addon.removeConfirmCode(username2);
      } else if (validCode != null) {
        player.sendMessage(Addon.getSerializer().deserialize(
            Settings.IMP.MAIN.STRINGS.LINK_WRONG_CODE.replace("{NICKNAME}", player.getUsername())));
      } else {
        Integer oldCode = this.addon.getCode(username2);
        if (oldCode != null && oldCode == enteredCode) {
          Addon.TempAccount tempAccount = this.addon.getTempAccount(username2);
          this.addon.linkSocial(username2, tempAccount.getDbField(), tempAccount.getId());
          this.addon.getSocialManager().registerHook(tempAccount.getDbField(), tempAccount.getId());
          this.addon.getSocialManager().broadcastMessage(
              tempAccount.getDbField(), tempAccount.getId(),
              Settings.IMP.MAIN.STRINGS.LINK_SUCCESS, this.addon.getKeyboard());
          player.sendMessage(Addon.getSerializer().deserialize(Settings.IMP.MAIN.STRINGS.LINK_SUCCESS_GAME));
          this.addon.removeCode(username2);
        } else {
          player.sendMessage(Addon.getSerializer().deserialize(Settings.IMP.MAIN.STRINGS.LINK_AVAILABLE_OPTIONS));
        }
      }
    } catch (NumberFormatException ignored) {
      player.sendMessage(Addon.getSerializer().deserialize(Settings.IMP.MAIN.STRINGS.LINK_AVAILABLE_OPTIONS));
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Override
  public List<String> suggest(Invocation invocation) {
    return SimpleCommand.super.suggest(invocation);
  }

  @Override
  public boolean hasPermission(Invocation invocation) {
    return SimpleCommand.super.hasPermission(invocation);
  }
}
