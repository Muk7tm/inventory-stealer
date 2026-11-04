package com.inventorystealer.client;

import com.inventorystealer.menu.CorpseMenu;
import com.inventorystealer.menu.CorpseMenuData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Wide, short loot screen: corpse contents on the left, the looter's own inventory on the right,
 * victim/backpack tabs along the top (vanilla creative-tab sprites).
 */
public class CorpseScreen extends AbstractContainerScreen<CorpseMenu> {
    private static final int TAB_WIDTH = 26;
    private static final int TAB_HEIGHT = 32;
    private static final int TAB_OVERLAP = 4;
    private static final ResourceLocation[] TAB_SELECTED = tabSprites("selected");
    private static final ResourceLocation[] TAB_UNSELECTED = tabSprites("unselected");

    private static final int PANEL = 0xFFC6C6C6;
    private static final int HIGHLIGHT = 0xFFFFFFFF;
    private static final int SHADOW = 0xFF555555;
    private static final int SLOT_DARK = 0xFF373737;
    private static final int SLOT_FILL = 0xFF8B8B8B;
    private static final int LABEL = 0x404040;
    private static final int HINT = 0x707070;

    // Page controls, right-aligned over the corpse grid with clear gaps around the "1/2" label.
    private static final int NEXT_X = CorpseMenu.GRID_RIGHT - 12;
    private static final int PAGE_LABEL_CENTER = NEXT_X - 15;
    private static final int PREV_X = PAGE_LABEL_CENTER - 15 - 12;

    private Button previousPage;
    private Button nextPage;

    public CorpseScreen(CorpseMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = CorpseMenu.IMAGE_WIDTH;
        this.imageHeight = CorpseMenu.IMAGE_HEIGHT;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = CorpseMenu.LOOTER_X;
        this.inventoryLabelY = 6;
    }

    private static ResourceLocation[] tabSprites(String state) {
        ResourceLocation[] sprites = new ResourceLocation[7];
        for (int i = 0; i < sprites.length; i++) {
            sprites[i] = ResourceLocation.withDefaultNamespace("container/creative_inventory/tab_top_" + state + "_" + (i + 1));
        }
        return sprites;
    }

    @Override
    protected void init() {
        super.init();
        // Centre panel + tab row together.
        int tabRoom = TAB_HEIGHT - TAB_OVERLAP;
        this.topPos = Math.max(tabRoom, (this.height - this.imageHeight + tabRoom) / 2);
        previousPage = addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(leftPos + PREV_X, topPos + 4, 12, 12).build());
        nextPage = addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(leftPos + NEXT_X, topPos + 4, 12, 12).build());
        updatePageButtons();
    }

    // ---------------------------------------------------------------- tabs & pages

    private int tabCount() {
        return 1 + menu.data().tabs().size();
    }

    private int tabStep() {
        return Math.min(TAB_WIDTH + 1, (imageWidth - TAB_WIDTH) / Math.max(1, tabCount() - 1));
    }

    private int tabX(int index) {
        return leftPos + index * tabStep();
    }

    private int tabY() {
        return topPos - TAB_HEIGHT + TAB_OVERLAP;
    }

    /** Tab index (0 = victim, 1.. = backpacks) under the mouse, or -1. Later tabs are drawn on top, so test them first. */
    private int tabAt(double mouseX, double mouseY) {
        for (int i = tabCount() - 1; i >= 0; i--) {
            int x = tabX(i);
            if (mouseX >= x && mouseX < x + TAB_WIDTH && mouseY >= tabY() && mouseY < topPos) {
                return i;
            }
        }
        return -1;
    }

    private int selectedTab() {
        return menu.views().get(menu.getActiveView()).tab() + 1;
    }

    private void selectView(int view) {
        if (!menu.isViewAvailable(view) || view == menu.getActiveView()) {
            return;
        }
        menu.selectViewClient(view);
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, view);
        }
        updatePageButtons();
    }

    private void changePage(int delta) {
        CorpseMenu.View view = menu.views().get(menu.getActiveView());
        int target = menu.getActiveView() + delta;
        if (view.tab() >= 0 && target >= 0 && target < menu.views().size() && menu.views().get(target).tab() == view.tab()) {
            selectView(target);
        }
    }

    private boolean isPaged() {
        CorpseMenu.View view = menu.views().get(menu.getActiveView());
        return view.tab() >= 0 && view.pageCount() > 1;
    }

    private void updatePageButtons() {
        if (previousPage == null) {
            return;
        }
        CorpseMenu.View view = menu.views().get(menu.getActiveView());
        boolean paged = isPaged();
        previousPage.visible = paged;
        nextPage.visible = paged;
        previousPage.active = paged && view.page() > 0;
        nextPage.active = paged && view.page() < view.pageCount() - 1;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updatePageButtons();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int tab = tabAt(mouseX, mouseY);
        if (tab >= 0 && button == 0) {
            if (tab != selectedTab()) {
                selectView(menu.firstViewOf(tab - 1));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int mouseButton) {
        return tabAt(mouseX, mouseY) < 0 && super.hasClickedOutside(mouseX, mouseY, guiLeft, guiTop, mouseButton);
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTabIcons(graphics);
        renderTooltip(graphics, mouseX, mouseY);
        int tab = tabAt(mouseX, mouseY);
        if (tab >= 0) {
            graphics.renderComponentTooltip(font, tabTooltip(tab), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int selected = selectedTab();
        for (int i = 0; i < tabCount(); i++) {
            if (i != selected) {
                graphics.blitSprite(TAB_UNSELECTED[Math.min(i, 6)], tabX(i), tabY(), TAB_WIDTH, TAB_HEIGHT);
            }
        }

        drawPanel(graphics, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                drawSlot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
            }
        }

        graphics.blitSprite(TAB_SELECTED[Math.min(selected, 6)], tabX(selected), tabY(), TAB_WIDTH, TAB_HEIGHT);
    }

    private void renderTabIcons(GuiGraphics graphics) {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 300.0F);
        for (int i = 0; i < tabCount(); i++) {
            int x = tabX(i) + 5;
            int y = tabY() + 9;
            if (i == 0) {
                PlayerFaceRenderer.draw(graphics, victimSkin(), x, y, 16);
            } else {
                graphics.renderItem(menu.data().tabs().get(i - 1).icon(), x, y);
                if (!menu.isTabValid(i - 1)) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(0.0F, 0.0F, 200.0F);
                    graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xAA2A2A2A);
                    graphics.drawString(font, "✖", x + 4, y + 4, 0xFF5555, false);
                    graphics.pose().popPose();
                }
            }
        }
        graphics.pose().popPose();
    }

    private ResourceLocation victimSkin() {
        PlayerInfo info = minecraft == null || minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(menu.data().victimId());
        return info != null ? info.getSkin().texture() : DefaultPlayerSkin.get(menu.data().victimId()).texture();
    }

    private List<Component> tabTooltip(int tab) {
        List<Component> lines = new ArrayList<>();
        if (tab == 0) {
            lines.add(Component.translatable("inventorystealer.gui.tab.player", menu.data().victimName()));
            return lines;
        }
        CorpseMenuData.Tab info = menu.data().tabs().get(tab - 1);
        lines.add(Component.translatable("inventorystealer.gui.tab.backpack", tab, info.icon().getHoverName()));
        lines.add(info.location().copy().withStyle(ChatFormatting.GRAY));
        if (!menu.isTabValid(tab - 1)) {
            lines.add(Component.translatable("inventorystealer.gui.tab.taken").withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        CorpseMenu.View view = menu.views().get(menu.getActiveView());

        // Left half: what is being looted (+ page controls for large backpacks).
        Component heading = view.tab() < 0
                ? Component.translatable("inventorystealer.gui.title.player", menu.data().victimName())
                : Component.translatable("inventorystealer.gui.title.backpack", view.tab() + 1);
        int headingRight = isPaged() ? PREV_X - 4 : CorpseMenu.GRID_RIGHT;
        graphics.drawString(font, font.plainSubstrByWidth(heading.getString(), headingRight - titleLabelX), titleLabelX, titleLabelY, LABEL, false);
        if (isPaged()) {
            String page = (view.page() + 1) + "/" + view.pageCount();
            graphics.drawString(font, page, PAGE_LABEL_CENTER - font.width(page) / 2, titleLabelY + 1, LABEL, false);
        }
        if (view.tab() < 0 && menu.data().extraIsEquipment().contains(Boolean.FALSE)) {
            graphics.drawString(font, Component.translatable("inventorystealer.gui.carried"), CorpseMenu.GRID_X, CorpseMenu.EXTRAS_Y - 11, LABEL, false);
        }

        // Right half: own inventory label, timer, hint.
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL, false);
        int seconds = Mth.ceil(menu.getRemainingTicks() / 20.0F);
        String timer = String.format("%d:%02d", seconds / 60, seconds % 60);
        graphics.drawString(font, timer, imageWidth - 8 - font.width(timer), titleLabelY, seconds <= 3 ? 0xC02020 : LABEL, false);
        int hintY = CorpseMenu.LOOTER_HOTBAR_Y + 26;
        for (var line : font.split(Component.translatable("inventorystealer.gui.hint"), CorpseMenu.IMAGE_WIDTH - 8 - CorpseMenu.LOOTER_X)) {
            graphics.drawString(font, line, CorpseMenu.LOOTER_X, hintY, HINT, false);
            hintY += 10;
        }
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x + 1, y, x + width - 1, y + height, 0xFF000000);
        graphics.fill(x, y + 1, x + width, y + height - 1, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL);
        graphics.fill(x + 1, y + 1, x + width - 3, y + 3, HIGHLIGHT);
        graphics.fill(x + 1, y + 1, x + 3, y + height - 3, HIGHLIGHT);
        graphics.fill(x + 3, y + height - 3, x + width - 1, y + height - 1, SHADOW);
        graphics.fill(x + width - 3, y + 3, x + width - 1, y + height - 1, SHADOW);
    }

    private static void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, SLOT_FILL);
        graphics.fill(x, y, x + 17, y + 1, SLOT_DARK);
        graphics.fill(x, y, x + 1, y + 17, SLOT_DARK);
        graphics.fill(x + 1, y + 17, x + 18, y + 18, HIGHLIGHT);
        graphics.fill(x + 17, y + 1, x + 18, y + 18, HIGHLIGHT);
    }
}
