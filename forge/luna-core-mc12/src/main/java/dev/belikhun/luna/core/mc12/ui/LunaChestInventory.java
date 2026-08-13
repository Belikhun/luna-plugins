package dev.belikhun.luna.core.mc12.ui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.world.IInteractionObject;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * How a custom container is opened at all on 1.12.2.
 *
 * `EntityPlayerMP.displayGUIChest` takes an `IInventory` and, unless that object
 * is also an `IInteractionObject`, wraps it in a **vanilla** `ContainerChest` -
 * which has no buttons and no click handling. Implementing both is the only hook
 * this version offers for supplying a container of our own, and it is why the
 * modern one-liner (`player.openMenu(new SimpleMenuProvider(...))`) has no
 * equivalent here.
 *
 * The inventory methods delegate to the menu's backing store. They exist because
 * the interface demands them, not because anything reads through this object: the
 * window's contents are written through the menu, and vanilla only asks this for
 * the size and the title when it sends the open packet.
 */
final class LunaChestInventory implements IInventory, IInteractionObject {
	private final LunaChestMenu menu;
	private final ITextComponent title;

	LunaChestInventory(LunaChestMenu menu, ITextComponent title) {
		this.menu = menu;
		this.title = plain(title);
	}

	/**
	 * A window title with every colour code removed.
	 *
	 * The game draws a container's title as dark text on the light grey inventory
	 * background, while luna's palette is written for chat, which is dark. An aqua
	 * heading that reads perfectly in chat is close to invisible here, so the title
	 * is stripped back to vanilla's own styling rather than tinted.
	 *
	 * Only titles. The items inside the window are drawn on the same dark ground as
	 * chat and keep their colours.
	 */
	private static ITextComponent plain(ITextComponent title) {
		if (title == null) {
			return new TextComponentString("");
		}

		return new TextComponentString(
			TextFormatting.getTextWithoutFormattingCodes(title.getFormattedText())
		);
	}

	@Override
	public Container createContainer(InventoryPlayer playerInventory, EntityPlayer player) {
		return menu;
	}

	/**
	 * The window kind the client is told to draw.
	 *
	 * Always a generic container: the client picks the row count from the size in
	 * the same packet, so unlike the modern builds there is no per-height type.
	 */
	@Override
	public String getGuiID() {
		return "minecraft:container";
	}

	@Override
	public int getSizeInventory() {
		return menu.containerSize();
	}

	@Override
	public ITextComponent getDisplayName() {
		return title;
	}

	@Override
	public String getName() {
		return title.getUnformattedText();
	}

	@Override
	public boolean hasCustomName() {
		return true;
	}

	@Override
	public boolean isEmpty() {
		return menu.inventory().isEmpty();
	}

	@Override
	public ItemStack getStackInSlot(int index) {
		return menu.inventory().getStackInSlot(index);
	}

	@Override
	public ItemStack decrStackSize(int index, int count) {
		return ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeStackFromSlot(int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public void setInventorySlotContents(int index, ItemStack stack) {
		menu.inventory().setInventorySlotContents(index, stack);
	}

	@Override
	public int getInventoryStackLimit() {
		return 64;
	}

	@Override
	public void markDirty() {
	}

	@Override
	public boolean isUsableByPlayer(EntityPlayer player) {
		return true;
	}

	@Override
	public void openInventory(EntityPlayer player) {
	}

	@Override
	public void closeInventory(EntityPlayer player) {
	}

	@Override
	public boolean isItemValidForSlot(int index, ItemStack stack) {
		return false;
	}

	@Override
	public int getField(int id) {
		return 0;
	}

	@Override
	public void setField(int id, int value) {
	}

	@Override
	public int getFieldCount() {
		return 0;
	}

	@Override
	public void clear() {
		menu.clearTopSlots();
	}
}
