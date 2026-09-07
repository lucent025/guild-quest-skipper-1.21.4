package dev.azurite.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class GuildQuestSkipperClient implements ClientModInitializer {

	private static KeyBinding activateKey;

	// =========================
	// CONFIG
	// =========================

	// 5 phút = 300 giây = 6000 ticks
	private static final int LOOP_DELAY = 20 * 60 * 5;

	// Chờ sau khi /guild
	private static final int GUILD_GUI_TIMEOUT = 20 * 10;

	// Chờ sau khi click Compass
	private static final int TASK_GUI_TIMEOUT = 20 * 10;

	// Chờ server cập nhật GUI sau QUICK_MOVE
	private static final int AFTER_QUICK_MOVE_DELAY = 10;

	// =========================
	// STATE
	// =========================

	private enum State {
		IDLE,

		OPENING_GUILD,
		WAITING_GUILD_GUI,

		CLICKING_COMPASS,
		WAITING_TASK_GUI,

		CHECKING_TASK,
		QUICK_MOVING,

		CLOSING_GUI,

		WAITING_NEXT_CYCLE
	}

	private static State state = State.IDLE;

	private static int timer = 0;

	private static int originalSyncId = -1;

	// =========================
	// MAIN TICK
	// =========================
	private static void tick(MinecraftClient client) {
		if (client.player == null || client.getNetworkHandler() == null) {
			return;
		}

		// =========================
		// K → START
		// =========================

		while (activateKey.wasPressed()) {

			if (state == State.IDLE) {

				// BẬT MOD
				startCycle(client);

				client.player.sendMessage(
						Text.literal("§a[Auto Skip Quest] §fĐã bật"),
						true
				);

			} else {

				// TẮT MOD
				stopBot(client);

				client.player.sendMessage(
						Text.literal("§c[Auto Skip Quest] §fĐã tắt"),
						true
				);
			}
		}

		// =========================
		// STATE MACHINE
		// =========================

		switch (state) {

			case IDLE:
				break;

			case OPENING_GUILD:
				openGuild(client);
				break;

			case WAITING_GUILD_GUI:
				waitForGuildGui(client);
				break;

			case CLICKING_COMPASS:
				clickCompass(client);
				break;

			case WAITING_TASK_GUI:
				waitForTaskGui(client);
				break;

			case CHECKING_TASK:
				checkGuildTask(client);
				break;

			case QUICK_MOVING:
				finishQuickMove(client);
				break;

			case CLOSING_GUI:
				closeGui(client);
				break;

			case WAITING_NEXT_CYCLE:
				waitNextCycle(client);
				break;
		}
	}

	// =========================
	// START
	// =========================
	private static void startCycle(MinecraftClient client)  {
		timer = 0;

		System.out.println("[GuildTaskBot] Starting cycle...");

		state = State.OPENING_GUILD;
	}

	// =========================
	// /GUILD
	// =========================
	private static void openGuild(MinecraftClient client) {
		client.getNetworkHandler().sendChatCommand("guild");

		timer = 0;

		state = State.WAITING_GUILD_GUI;

		System.out.println("[GuildTaskBot] Sent /guild");
	}

	// =========================
	// WAIT GUILD GUI
	// =========================
	private static void waitForGuildGui(MinecraftClient client) {
		timer++;

		if (isHandledGui(client)) {

			/*
			 * Đã mở GUI.
			 */
			timer = 0;

			state = State.CLICKING_COMPASS;

			System.out.println("[GuildTaskBot] Guild GUI detected");

			return;
		}

		if (timer >= GUILD_GUI_TIMEOUT) {

			System.out.println("[GuildTaskBot] Guild GUI timeout");

			state = State.WAITING_NEXT_CYCLE;
			timer = 0;
		}
	}

	// =========================
	// FIND COMPASS
	// =========================
	private static void clickCompass(MinecraftClient client) {
		ScreenHandler handler;
        if (client.player != null) {
			handler = client.player.currentScreenHandler;
        } else return;

        Slot compassSlot = findCompassSlot(client, handler);

		if (compassSlot == null) {

			timer++;

			if (timer >= GUILD_GUI_TIMEOUT) {

				System.out.println("[GuildTaskBot] Compass not found");

				state = State.CLOSING_GUI;
				timer = 0;
			}

			return;
		}

		System.out.println("[GuildTaskBot] Compass found at slot " + compassSlot.id);

		/*
		 * Click trái vào Compass.
		 *
		 * Không đụng vào chuột vật lý.
		 */
        if (client.interactionManager != null) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    compassSlot.id,
                    0,
                    SlotActionType.PICKUP,
                    client.player
            );
        } else return;

        originalSyncId = handler.syncId;

		timer = 0;

		state = State.WAITING_TASK_GUI;
	}

	// =========================
	// FIND COMPASS SLOT
	// =========================
	private static Slot findCompassSlot(MinecraftClient client, ScreenHandler handler) {
		for (Slot slot : handler.slots) {

			/*
			 * Không kiểm tra inventory của Player.
			 *
			 * Chỉ tìm trong container của GUI.
			 */
			if (client.player != null) {
				if (slot.inventory == client.player.getInventory()) {
					continue;
				}
			} else return null;

			ItemStack stack = slot.getStack();

			if (stack.isEmpty()) {
				continue;
			}

			if (stack.isOf(Items.COMPASS)) {
				return slot;
			}
		}

		return null;
	}

	// =========================
	// WAIT TASK GUI
	// =========================
	private static void waitForTaskGui(MinecraftClient client) {
		timer++;

		if (!isHandledGui(client)) {

			if (timer >= TASK_GUI_TIMEOUT) {

				System.out.println("[GuildTaskBot] Task GUI timeout");

				state = State.CLOSING_GUI;
				timer = 0;
			}

			return;
		}

		/*
		 * syncId thay đổi → rất có khả năng
		 * server đã mở GUI mới.
		 */
		ScreenHandler handler;
        if (client.player != null) {
			handler = client.player.currentScreenHandler;
        } else return;

        if (handler.syncId != originalSyncId) {

			System.out.println("[GuildTaskBot] Task GUI detected");

			timer = 0;

			state = State.CHECKING_TASK;
		}
	}

	// =========================
	// CHECK TASK
	// =========================
	private static void checkGuildTask(MinecraftClient client) {
		ScreenHandler handler;
        if (client.player != null) {
			handler  = client.player.currentScreenHandler;
        } else return;

		Slot taskSlot = findActualTaskClock(client, handler);
		if (taskSlot == null) {

			System.out.println("[GuildTaskBot] Task clock not found");

			state = State.CLOSING_GUI;
			timer = 0;

			return;
		}

		ItemStack stack = taskSlot.getStack();

		System.out.println(
				"[GuildTaskBot] Task found in slot " + taskSlot.id
		);

		printItemInfo(stack);

		if (isEnchantTask(stack)) {

			System.out.println("[GuildTaskBot] ENCHANT task → KEEP");

			state = State.CLOSING_GUI;
			timer = 0;

			return;
		}

		if (isReforgeTask(stack)) {

			System.out.println("[GuildTaskBot] REFORGE task → KEEP");

			state = State.CLOSING_GUI;
			timer = 0;

			return;
		}

		/*
		 * Không phải Enchant hoặc Reforge.
		 */
		System.out.println("[GuildTaskBot] Unwanted task → QUICK_MOVE");

        if (client.interactionManager != null) {
            client.interactionManager.clickSlot(
                    handler.syncId,
                    taskSlot.id,
                    0,
                    SlotActionType.QUICK_MOVE,
                    client.player
            );
        } else return;

        timer = 0;

		state = State.QUICK_MOVING;
	}

	// =========================
	// FIND TASK CLOCK
	// =========================
	private static Slot findActualTaskClock(MinecraftClient client, ScreenHandler handler) {
		for (Slot slot : handler.slots) {

			/*
			 * Không đụng Player Inventory.
			 */
			if (slot.inventory == (client.player != null ? client.player.getInventory() : null)) {
				continue;
			}

			ItemStack stack = slot.getStack();

			if (stack.isEmpty()) {
				continue;
			}

			/*
			 * Chỉ quan tâm Clock.
			 */
			if (!stack.isOf(Items.CLOCK)) {
				continue;
			}

			String name = stack.getName().getString();

			/*
			 * Có 2 Clock.
			 *
			 * Clock có tên:
			 * "THÔNG TIN NHIỆM VỤ"
			 *
			 * → bỏ qua.
			 *
			 * Clock còn lại:
			 * → nhiệm vụ cần kiểm tra.
			 */

			if (name.equalsIgnoreCase("THÔNG TIN NHIỆM VỤ")) {

				continue;
			}

			return slot;
		}

		return null;
	}

	// =========================
	// CHECK ENCHANT
	// =========================
	private static boolean isEnchantTask(ItemStack stack) {
		String name = stack.getName().getString();

		if (containsIgnoreCase(name, "Enchant")) {
			return true;
		}

		LoreComponent lore = stack.get(DataComponentTypes.LORE);

		if (lore != null) {

			for (Text line : lore.lines()) {

				String text = line.getString();

				if (containsIgnoreCase(text, "Enchant")) {
					return true;
				}
			}
		}

		return false;
	}

	// =========================
	// CHECK REFORGE
	// =========================
	private static boolean isReforgeTask(ItemStack stack) {
		String name = stack.getName().getString();

		if (containsIgnoreCase(name, "Reforge")) {
			return true;
		}

		LoreComponent lore = stack.get(DataComponentTypes.LORE);

		if (lore != null) {

			for (Text line : lore.lines()) {

				String text = line.getString();

				if (containsIgnoreCase(text, "Reforge")) {
					return true;
				}
			}
		}

		return false;
	}

	// =========================
	// STRING HELPER
	// =========================
	private static boolean containsIgnoreCase(String text, String search) {
		return text
				.toLowerCase()
				.contains(search.toLowerCase());
	}

	// =========================
	// PRINT ITEM INFO
	// =========================
	private static void printItemInfo(ItemStack stack) {
		System.out.println(
				"========== GUILD TASK =========="
		);

		System.out.println(
				"Name: " + stack.getName().getString()
		);

		LoreComponent lore = stack.get(DataComponentTypes.LORE);

		if (lore != null) {

			for (Text line : lore.lines()) {

				System.out.println(
						"Lore: " + line.getString()
				);
			}
		}

		System.out.println(
				"================================"
		);
	}

	// =========================
	// AFTER QUICK MOVE
	// =========================
	private static void finishQuickMove(MinecraftClient client) {
		timer++;

		/*
		 * Cho server vài tick để cập nhật GUI.
		 */
		if (timer >= AFTER_QUICK_MOVE_DELAY) {

			state = State.CLOSING_GUI;
			timer = 0;
		}
	}

	// =========================
	// CLOSE GUI
	// =========================
	private static void closeGui(MinecraftClient client) {
		if (client.currentScreen != null) {
			client.currentScreen.close();
		}

		timer = 0;

		state = State.WAITING_NEXT_CYCLE;

		System.out.println(
				"[GuildTaskBot] Cycle completed"
		);
	}

	// =========================
	// WAIT 5 MINUTES
	// =========================
	private static void waitNextCycle(MinecraftClient client) {
		timer++;

		if (timer >= LOOP_DELAY) {

			System.out.println(
					"[GuildTaskBot] 5 minutes elapsed"
			);

			timer = 0;

			state = State.OPENING_GUILD;
		}
	}

	// =========================
	// GUI CHECK
	// =========================
	private static boolean isHandledGui(MinecraftClient client) {
		return client.currentScreen
				instanceof HandledScreen<?>;
	}

	// =========================
	// STOP
	// =========================
	private static void stopBot(MinecraftClient client) {

		state = State.IDLE;
		timer = 0;

		System.out.println("[GuildTaskBot] STOPPED");
	}

	// =========================
	// INITIALIZE
	// =========================
	@Override
	public void onInitializeClient() {

		activateKey = KeyBindingHelper.registerKeyBinding(
				new KeyBinding(
						"key.guildtaskbot.activate",
						InputUtil.Type.KEYSYM,
						GLFW.GLFW_KEY_K,
						"category.guildtaskbot"
				)
		);

		ClientTickEvents.END_CLIENT_TICK.register(
				GuildQuestSkipperClient::tick
		);

	}
}