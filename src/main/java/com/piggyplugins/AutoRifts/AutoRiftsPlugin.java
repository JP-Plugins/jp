package com.piggyplugins.AutoRifts;

import com.example.EthanApiPlugin.Collections.*;
import com.example.PacketUtils.WidgetInfoExtended;
import com.example.Packets.*;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.piggyplugins.AutoRifts.data.*;
import com.piggyplugins.AutoRifts.data.Constants;
import com.piggyplugins.PiggyUtils.API.InventoryUtil;
import com.piggyplugins.PiggyUtils.API.ObjectUtil;
import com.piggyplugins.PiggyUtils.BreakHandler.ReflectBreakHandler;
import com.example.EthanApiPlugin.EthanApiPlugin;
import com.example.InteractionApi.InventoryInteraction;
import com.example.InteractionApi.NPCInteraction;
import com.example.InteractionApi.TileObjectInteraction;
import com.example.PacketUtils.PacketUtilsPlugin;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import org.apache.commons.lang3.RandomUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@PluginDependency(PacketUtilsPlugin.class)
@PluginDependency(EthanApiPlugin.class)
@PluginDescriptor(
        name = "<html><font color=\"#FF9DF9\">[PP]</font> Auto Rifts</html>",
        description = "Guardians of the Rift",
        enabledByDefault = false,
        tags = {"ethan", "piggy"}
)
@Slf4j
public class AutoRiftsPlugin extends Plugin {
    private int elementalRewardPoints=-2;
    private int catalyticRewardPoints=-2;

    // thx gotr helper plugin, borrowed some code from u
    // https://github.com/DatBear/Guardians-of-the-Rift-Helper

    private static final String REWARD_POINT_REGEX = "Total elemental energy:[^>]+>([\\d,]+).*Total catalytic energy:[^>]+>([\\d,]+).";
    private static final Pattern REWARD_POINT_PATTERN = Pattern.compile(REWARD_POINT_REGEX);
    private static final String CHECK_POINT_REGEX = "You have (\\d+) catalytic energy and (\\d+) elemental energy";
    private static final Pattern CHECK_POINT_PATTERN = Pattern.compile(CHECK_POINT_REGEX);
    //Have to clean up the code, if you're reading this and want to clean it up, feel free to PR.
    // It's a fucking MESS rn and i cba cleaning it up after getting it to a proper state - Trinity
    private List<Pouch>pouches = new ArrayList<Pouch>();

    private int essenceInPouches = 0;
    private static final Set<Integer> MINING_ANIMATION_IDS = Set.of(AnimationID.MINING_ADAMANT_PICKAXE, AnimationID.MINING_TRAILBLAZER_PICKAXE, AnimationID.MINING_BLACK_PICKAXE, AnimationID.MINING_BRONZE_PICKAXE, AnimationID.MINING_CRYSTAL_PICKAXE, AnimationID.MINING_DRAGON_PICKAXE, AnimationID.MINING_GILDED_PICKAXE, AnimationID.MINING_INFERNAL_PICKAXE, AnimationID.MINING_MITHRIL_PICKAXE, AnimationID.MINING_RUNE_PICKAXE, AnimationID.MINING_DRAGON_PICKAXE_OR, AnimationID.MINING_DRAGON_PICKAXE_OR_TRAILBLAZER, AnimationID.MINING_DRAGON_PICKAXE_UPGRADED, AnimationID.MINING_IRON_PICKAXE, AnimationID.MINING_STEEL_PICKAXE, AnimationID.MINING_TRAILBLAZER_PICKAXE_3, AnimationID.MINING_TRAILBLAZER_PICKAXE_2, AnimationID.MINING_3A_PICKAXE);

    @Inject
    private Client client;
    @Inject
    private EthanApiPlugin api;
    @Inject
    private MovementPackets movementPackets;
    @Inject
    private ObjectPackets objectPackets;
    @Inject
    private MousePackets mousePackets;
    @Inject
    private TileItemPackets tileItemPackets;
    @Inject
    private ReflectBreakHandler breakHandler;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private KeyManager keyManager;
    @Inject
    private AutoRiftsOverlay overlay;
    @Inject
    private AutoRiftsConfig config;

    @Provides
    private AutoRiftsConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(AutoRiftsConfig.class);
    }

    private static final Set<Integer> GOTR_REGIONS = Set.of(14483, 14484);

    @Getter(AccessLevel.PACKAGE)
    private final Set<GameObject> guardians = new HashSet<>();
    @Getter(AccessLevel.PACKAGE)
    private final Set<GameObject> activeGuardians = new HashSet<>();
    @Getter
    private State state;
    private int timeout;
    private boolean gameStarted;
    private Set<Altar> accessibleAltars;
    @Getter
    private boolean started;
    private Instant timer;
    private long pauseTime;
    private boolean attackStarted;

    @Override
    protected void startUp() throws Exception {
        setPouches();
        this.overlayManager.add(overlay);
        this.keyManager.registerKeyListener(this.toggle);
        this.breakHandler.registerPlugin(this);
        this.timer = Instant.now();
    }

    @Override
    protected void shutDown() throws Exception {
        pouches.clear();
        this.keyManager.unregisterKeyListener(this.toggle);
        this.breakHandler.unregisterPlugin(this);
        this.breakHandler.stopPlugin(this);
        this.overlayManager.remove(overlay);
    }
    int temp =0;
    @Subscribe
    private void onGameTick(GameTick event) {
        if (client.getGameState() != GameState.LOGGED_IN || !started) {
            return;
        }

        if (catalyticRewardPoints == -2 && elementalRewardPoints == -2) {
            Optional<Widget> dialog = Widgets.search().withId(15007745).first();
            if (dialog.isPresent()) {
                String dialogText = dialog.get().getText();
                final Matcher checkMatcher = CHECK_POINT_PATTERN.matcher(dialogText);
                if (checkMatcher.find(0)) {
                    catalyticRewardPoints = Integer.parseInt(checkMatcher.group(1));
                    elementalRewardPoints = Integer.parseInt(checkMatcher.group(2));
                    return;
                }
            }
        }

        if (config.usePouches()&&Inventory.search().withId(ItemID.RUNE_POUCH).empty()&&Inventory.search().withId(ItemID.DIVINE_RUNE_POUCH).empty()){
            client.addChatMessage(ChatMessageType.GAMEMESSAGE,"","Must have a rune pouch with NPC contact Runes to use essence pouches",null);
            EthanApiPlugin.stopPlugin(this);
        }

        if (pouches.size() == 0 && config.usePouches()) {
            setPouches();
        }

        if(Inventory.full()
                && getEmptyPouches().size()>0
                &&Inventory.search().withId(ItemID.GUARDIAN_ESSENCE).first().isPresent()){
            fillPouches();
        }

        if (!gameStarted && isWidgetVisible()) {
            gameStarted = true;
        }

        if (!isInAltar() && !isWidgetVisible()) {
            gameStarted = false;
            attackStarted = false;
        }

        this.accessibleAltars = Utility.getAccessibleAltars(client.getRealSkillLevel(Skill.RUNECRAFT),
                Quest.LOST_CITY.getState(client), Quest.TROLL_STRONGHOLD.getState(client),
                Quest.MOURNINGS_END_PART_II.getState(client), Quest.SINS_OF_THE_FATHER.getState(client));
        state = getCurrentState();
        handleState();
    }

    @Subscribe
    private void onChatMessage(ChatMessage event) {
        if (client.getGameState() != GameState.LOGGED_IN || !started) {
            return;
        }

        if (event.getType() != ChatMessageType.SPAM && event.getType() != ChatMessageType.GAMEMESSAGE) return;

        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        if (event.getMessage().contains("5 seconds.")) {
            int attempt = RandomUtils.nextInt(0,10);
            if (attempt > 7){
                if (client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) == 1000) {
                    if (!Equipment.search().matchesWildCardNoCase("*Dragon pickaxe*").empty() || !Equipment.search().matchesWildCardNoCase("*infernal pickaxe*").empty()) {
                        MousePackets.queueClickPacket();
                        WidgetPackets.queueWidgetActionPacket(1, 38862884, -1, -1);
                    }
                }
            }
        }

        if(event.getMessage().contains("3..")){
            int attempt = RandomUtils.nextInt(0,5);
            if(attempt>2){
                if (client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) == 1000) {
                    if (!Equipment.search().matchesWildCardNoCase("*Dragon pickaxe*").empty()||!Equipment.search().matchesWildCardNoCase("*infernal pickaxe*").empty()) {
                        MousePackets.queueClickPacket();
                        WidgetPackets.queueWidgetActionPacket(1, 38862884, -1, -1);
                    }
                }
            }
        }

        if(event.getMessage().contains("2..")){
            if (client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) == 1000) {
                if (!Equipment.search().matchesWildCardNoCase("*Dragon pickaxe*").empty() || !Equipment.search().matchesWildCardNoCase("*infernal pickaxe*").empty()) {
                    MousePackets.queueClickPacket();
                    WidgetPackets.queueWidgetActionPacket(1, 38862884, -1, -1);
                }
            }
        }


        if (event.getMessage().contains(Constants.GAME_STARTED)) {
            gameStarted = true;
        }

        if (event.getMessage().contains(Constants.GAME_OVER)) {
            gameStarted = false;
            attackStarted = false;
        }

        if (event.getMessage().contains(Constants.GAME_WIN)) {
            gameStarted = false;
            attackStarted = false;
        }

        if (event.getMessage().contains(Constants.ATTACK_STARTED)) {
            attackStarted = true;
        }

        Matcher rewardPointMatcher = REWARD_POINT_PATTERN.matcher(event.getMessage());
        if(rewardPointMatcher.find()) {
            elementalRewardPoints = Integer.parseInt(rewardPointMatcher.group(1).replaceAll(",", ""));
            catalyticRewardPoints = Integer.parseInt(rewardPointMatcher.group(2).replaceAll(",", ""));
        }

    }

    private int tickDelay() {
        return ThreadLocalRandom.current().nextInt(0, 3);
    }

    private void handleState() {
        switch (state) {
            case GET_POINTS:
                getPoints();
                break;
            case MINING:
                break;
            case TIMEOUT:
                timeout--;
                break;
            case GAME_BUSY:
                if (RandomUtils.nextInt(0, 100) == 30){
                    TileObjectInteraction.interact(TileObjects.search().withId(Constants.BARRIER_BUSY_ID).first().get(),"Peek");
                }
                break;
            case OUTSIDE_BARRIER:
                enterGame();
                break;
            case LEAVE_LARGE:
            case RETURN_TO_START:
                climbLargeMine();
                break;
            case WAITING_FOR_GAME:
                waitForGame();
                break;
            case MINE_LARGE:
                mineLargeGuardians();
                break;
            case MINE_HUGE:
                mineHugeGuardians();
                break;
            case MINE_GAME:
                mineGameGuardians();
                break;
            case CRAFT_ESSENCE:
                craftEssence();
                break;
            case BUILD_GUARDIAN:
                break;
            case CHARGE_SHIELD:
                break;
            case DEPOSIT_RUNES:
                depositRunes();
                break;
            case ENTER_PORTAL:
                enterPortal();
                break;
            case ENTER_RIFT:
                enterRift();
                break;
            case CRAFT_RUNES:
                craftRunes();
                break;
            case EXIT_ALTAR:
                exitAltar();
                timeout = tickDelay();
                break;
            case POWER_GUARDIAN:
                powerGuardian();
                break;
            case REPAIR_POUCH:
                boolean hadBook = config.hadBook();
                if (!Widgets.search().withTextContains("What do you want?").hiddenState(false).empty() || !Widgets.search().withTextContains("Can you repair").hiddenState(false).empty()) {
                    MousePackets.queueClickPacket();
                    WidgetPackets.queueResumePause(15138821, -1);
                    MousePackets.queueClickPacket();
                    WidgetPackets.queueResumePause(14352385, hadBook ? 1 : 2);
                    MousePackets.queueClickPacket();
                    WidgetPackets.queueResumePause(14221317, -1);
                    MousePackets.queueClickPacket();
                    EthanApiPlugin.invoke(-1, -1, 26, -1, -1, "", "", -1, -1);
                    timeout = 0;
                    setPouches();
                    return;
                } else {
                    MousePackets.queueClickPacket();
                    WidgetPackets.queueWidgetActionPacket(2, WidgetInfoExtended.SPELL_NPC_CONTACT.getPackedId(),
                            -1, -1);
                    timeout = 15;
                }
                break;
            case TAKE_CELLS:
                takeCells();
                break;
            case DROP_RUNES:
                dropRunes();
                break;
            case DROP_TALISMAN:
                dropTalisman();
                break;
        }
    }

    private void dropTalisman() {
        Optional<Widget> itemWidget = InventoryUtil.nameContainsNoCase("talisman").first();
        if (itemWidget.isEmpty()) {
            return;
        }

        Widget item = itemWidget.get();
        InventoryInteraction.useItem(item, "Drop");
    }

    private void dropRunes() {
        Optional<Widget> itemWidget = InventoryUtil.nameContainsNoCase("rune").filter(item -> !item.getName().contains("pickaxe")).first();
        if (itemWidget.isEmpty()) {
            return;
        }

        Widget item = itemWidget.get();
        InventoryInteraction.useItem(item, "Drop");
    }

    private void getPoints(){
        if (isOutsideBarrier()) {
            Optional<TileObject> guardian = TileObjects.search().withId(43695).first();
            TileObjectInteraction.interact(guardian.get(),"Check");
        }
        timeout=tickDelay();
    }

    private void depositRunes() {
        Optional<TileObject> tileObject = TileObjects.search().withAction("Deposit-runes").nearestToPlayer();
        if (tileObject.isEmpty()) {
            return;
        }

        TileObject runeDeposit = tileObject.get();
        TileObjectInteraction.interact(runeDeposit, "Deposit-runes");
        timeout = tickDelay();
    }

    private void powerGuardian() {
        Optional<NPC> npc = NPCs.search().nameContains(Constants.GREAT_GUARDIAN).nearestToPlayer();
        if (npc.isEmpty()) {
            return;
        }

        NPC guardian = npc.get();
        NPCInteraction.interact(guardian, "Power-up");
        timeout = tickDelay();
    }

    private void exitAltar() {
        Optional<TileObject> tileObject = TileObjects.search().nameContains(Constants.PORTAL).nearestToPlayer();
        if (tileObject.isEmpty()) {
            return;
        }

        TileObject portal = tileObject.get();
        TileObjectInteraction.interact(portal, "Use");
        timeout = tickDelay();
    }

    private void craftRunes() {
        Optional<TileObject> tileObject = TileObjects.search().withAction(Constants.CRAFT_RUNES).nearestToPlayer();
        if (tileObject.isEmpty()) {
            return;
        }
        if(getEssenceInPouches()>0&&Inventory.getEmptySlots()>0){
            emptyPouches();
        }

        TileObject altar = tileObject.get();
        TileObjectInteraction.interact(altar, Constants.CRAFT_RUNES);
        timeout = tickDelay();
    }

    private void enterRift() {
        int catalytic;
        int elemental;
        TileObject catalyticAltar = null;
        TileObject elementalAltar = null;
        List<TileObject> activeAltars = new ArrayList<TileObject>();
        List<TileObject> guardians = TileObjects.search().nameContains("Guardian of").result();
        for (TileObject guardian:guardians) {
            GameObject gameObject = (GameObject) guardian;
            Animation animation = ((DynamicObject) gameObject.getRenderable()).getAnimation();
            if (animation.getId() == 9363) {
                activeAltars.add(guardian);
            }
        }

        for(TileObject altar : activeAltars){
            if (isCatalytic(altar)) {
                catalyticAltar = altar;
            } else {
                elementalAltar = altar;

            }
        }

        if (catalyticRewardPoints<0&&elementalRewardPoints<0){
             elemental = client.getVarbitValue(13686);
             catalytic = client.getVarbitValue(13685);
        } else {
             elemental = elementalRewardPoints;
             catalytic = catalyticRewardPoints;
        }

        if (catalytic == 0 && elemental == 0){
            elemental = 1;
        }
        Widget catalyticWidget = client.getWidget(48889879);
        Widget elementalWidget = client.getWidget(48889876);

        if (elementalWidget == null || catalyticWidget == null) {
            return;
        }
        if ((elemental>=catalytic || config.prioritizeCatalytic()) && catalyticAltar!=null){
            for(Altar altar : accessibleAltars){
                if (altar.getId() == catalyticAltar.getId()) {
                    TileObjectInteraction.interact(catalyticAltar,"Enter");
                    return;
                }
            }
            TileObjectInteraction.interact(elementalAltar,"Enter");
        } else if (elemental < catalytic && elementalAltar != null) {
            TileObjectInteraction.interact(elementalAltar,"Enter");
        }

        timeout = tickDelay();
    }

    public boolean isCatalytic(TileObject altar){
        Set<Integer> catalyticAltars = Set.of(43705,43709,43706,43710,43711,43708,43712,43707);
        return catalyticAltars.contains(altar.getId());
    }

    private void enterPortal() {
        Optional<TileObject> tileObject = ObjectUtil.nameContainsNoCase(Constants.PORTAL).filter(to -> to.getWorldLocation().getY() >= Constants.OUTSIDE_BARRIER_Y).nearestToPlayer();
        if (tileObject.isEmpty()) {
            return;
        }

        TileObject portal = tileObject.get();
        TileObjectInteraction.interact(portal, "Enter", "Exit", "Use");
        timeout = tickDelay();
    }

    private void craftEssence() {
        Optional<TileObject> tileObject = TileObjects.search().nameContains(Constants.WORKBENCH).nearestToPlayer();
        if (tileObject.isEmpty()) {
            return;
        }
        if(isMining() || client.getLocalPlayer().getAnimation() == -1){
            TileObject workbench = tileObject.get();
            TileObjectInteraction.interact(workbench, "Work-at");
            timeout = tickDelay();
        }
    }

    private void takeCells() {
        Optional<TileObject> tileObject = TileObjects.search().withAction("Take-10").nearestToPlayer();
        if (tileObject.isEmpty()) {
            return;
        }

        TileObject unchargedCells = tileObject.get();
        TileObjectInteraction.interact(unchargedCells, "Take-10");
        timeout = tickDelay();
    }

    private void climbLargeMine() {
        Optional<TileObject> tileObject = TileObjects.search().withAction("Climb").nearestToPlayer();
        if (tileObject.isEmpty()) {
            return;
        }

        TileObject rubble = tileObject.get();
        TileObjectInteraction.interact(rubble, "Climb");
        timeout = tickDelay();
    }

    private void mineHugeGuardians() {
        Optional<TileObject> tileObject = TileObjects.search().nameContains(Constants.HUGE_REMAINS).nearestToPlayer();
        if (tileObject.isEmpty()) {
            return;
        }
        if (client.getLocalPlayer().getAnimation() == -1) {
            TileObject remains = tileObject.get();
            TileObjectInteraction.interact(remains, "Mine");
            timeout=tickDelay();
        }
    }

    private void mineLargeGuardians() {
        Optional<TileObject> tileObject = TileObjects.search().nameContains(Constants.LARGE_REMAINS).nearestToPlayer();
        if (tileObject.isEmpty()) {
            return;
        }
        if (client.getLocalPlayer().getAnimation() == -1) {
            TileObject remains = tileObject.get();
            TileObjectInteraction.interact(remains, "Mine");
            timeout = tickDelay();
        }
    }

    private void mineGameGuardians() {
        Optional<TileObject> tileObject = ObjectUtil.nameContainsNoCase(Constants.GAME_PARTS).nearestToPlayer();
        if (tileObject.isEmpty()) {
            return;
        }
        if (client.getLocalPlayer().getAnimation() == -1){
            TileObject remains = tileObject.get();
            TileObjectInteraction.interact(remains, "Mine");
            timeout = tickDelay();
        }
    }

    private void enterGame() {
        Optional<TileObject> tileObject = TileObjects.search().nameContains("Barrier").withAction("Quick-pass").nearestToPlayer();
        if (tileObject.isEmpty()) {
            return;
        }
        TileObject barrier = tileObject.get();
        TileObjectInteraction.interact(barrier, "Quick-pass");
        timeout = tickDelay();
    }

    private void waitForGame() {
        if (client.getLocalPlayer().getWorldLocation().getX() == Constants.LARGE_MINE_X) {
            if (tickDelay() % 2 == 0) {
                MousePackets.queueClickPacket();
                MovementPackets.queueMovement(3639, 9500, false);
            } else {
                MousePackets.queueClickPacket();
                MovementPackets.queueMovement(3640, 9500, false);
            }
        }
    }

    public State getCurrentState() {
        if ((EthanApiPlugin.isMoving() || client.getLocalPlayer().getAnimation() != -1)){
            if (isCraftingEss() && !isPortalSpawned()) {
                return State.CRAFTING_ESS;
            }
            if (isMining() && isInLargeMine()) {
                if (getFrags() >= config.startingFrags()) {
                    return State.LEAVE_LARGE;
                } else {
                    return State.MINING;
                }
            }
            if (isMining()&&!isInHugeMine() && !isInLargeMine()) {
                if (hasEnoughFrags()) {
                    return State.CRAFT_ESSENCE;
                }

                if (isPortalSpawned()&&!Inventory.full()) {
                    return  State.ENTER_PORTAL;
                }
            }

            if (isMining()&&(!Inventory.full() && Inventory.getItemAmount(ItemID.GUARDIAN_FRAGMENTS) < config.minFrags())){
                return State.MINING;
            }

            if (isCraftingEss() && isPortalSpawned() && Inventory.getItemAmount(ItemID.GUARDIAN_ESSENCE)<config.ignorePortal()){
                if (timeout > 0) {
                    return State.TIMEOUT;
                } else {
                    return State.ENTER_PORTAL;
                }
            }
            return State.ANIMATING;
        }

        if (pouchesDegraded()) {
            return State.REPAIR_POUCH;
        }

        if (timeout > 0 && state != State.WAITING) {
            timeout--;
            return State.TIMEOUT;
        }

        if (isGameBusy()) {
            return State.GAME_BUSY;
        }

        if (isOutsideBarrier() && !isInAltar() && !isGameBusy()) {
            if (elementalRewardPoints < 0 && catalyticRewardPoints < 0) {
                return State.GET_POINTS;
            }
            return State.OUTSIDE_BARRIER;
        }

        if (isInAltar()) {
            if (!gameStarted) {
              return State.EXIT_ALTAR;
            }
            if (hasAnyGuardianEssence() || getEssenceInPouches() > 0) {
                return State.CRAFT_RUNES;
            }
            return State.EXIT_ALTAR;
        }
        if (hasPowerEssence() && gameStarted) {
            return State.POWER_GUARDIAN;
        }

        if (shouldDepositRunes()) {
            if (config.dropRunes()) {
                return State.DROP_RUNES;
            }
            return State.DEPOSIT_RUNES;
        }

        if (hasTalisman()) {
            return State.DROP_TALISMAN;
        }

        if (isInLargeMine()) {
            if(isPortalSpawned() || hasEnoughStartingFrags()) {
                return State.LEAVE_LARGE;
            }
            if (!gameStarted) {
                return State.WAITING_FOR_GAME;
            } else {
                return State.MINE_LARGE;
            }
        }

        if (isPortalSpawned() && !Inventory.full()) {
            if (isInHugeMine() && gameStarted) {
               return Inventory.full() ? State.ENTER_PORTAL : State.MINE_HUGE;
            }

            if (isInHugeMine() && !gameStarted) {
                return State.ENTER_PORTAL;
            }

            if (!gameStarted) {
                return State.RETURN_TO_START;
            }

            return State.ENTER_PORTAL;
        }

        if (isInHugeMine()) {
            if (gameStarted) {
                return Inventory.full() ? State.ENTER_PORTAL : State.MINE_HUGE;
            } else {
                return State.ENTER_PORTAL;
            }
        }

        if (hasGuardianEssence()) {
            return State.ENTER_RIFT;
        }

        if (hasEnoughFrags() && !isInLargeMine()) {
            return State.CRAFT_ESSENCE;
        }

        if (!hasEnoughFrags() && getFrags() >= Inventory.getEmptySlots() + getRemainingEssence()) {
            return State.CRAFT_ESSENCE;
        }

        if (!hasEnoughFrags() && gameStarted && !isInLargeMine() && !isInHugeMine() && !isPortalSpawned()) {
            return State.MINE_GAME;
        }

        if (!gameStarted && EthanApiPlugin.playerPosition().getX() != Constants.LARGE_MINE_X){
            if (isInHugeMine()) {
                return State.ENTER_PORTAL;
            }
            return State.RETURN_TO_START;
        }

        return State.WAITING;
    }

    private boolean hasTalisman() {
        return InventoryUtil.nameContainsNoCase("talisman").first().isPresent();
    }

    private boolean hasUnchargedCells() {
        return InventoryUtil.hasItem(Constants.UNCHARGED_CELLS);
    }

    private boolean hasPowerEssence() {
        return InventoryUtil.hasItem(Constants.CATALYTIC_ENERGY) || InventoryUtil.hasItem(Constants.ELEMENTAL_ENERGY);
    }

    private boolean shouldDepositRunes() {
        return !InventoryUtil.nameContainsNoCase("rune").filter(item -> !item.getName().contains("pouch")).empty();
    }
    
    public int getAlternative(Widget pouch) {
        int alternative = -1;
        switch (pouch.getItemId()) {
            case ItemID.MEDIUM_POUCH:
                alternative = ItemID.MEDIUM_POUCH_5511;
                break;
            case ItemID.LARGE_POUCH:
                alternative = ItemID.LARGE_POUCH_5513;
                break;
            case ItemID.GIANT_POUCH:
                alternative = ItemID.GIANT_POUCH_5515;
                break;
            case ItemID.COLOSSAL_POUCH:
                alternative = ItemID.COLOSSAL_POUCH_26786;
                break;
        }
        return alternative;
    }
    
    public List<Pouch> getPouches(){
        return pouches;
    }

    public int getEssenceInPouches() {
        List<Pouch> allEssPouches = getPouches();
        int essenceInPouches = 0;
        for (Pouch curr : allEssPouches) {
            essenceInPouches += curr.getCurrentEssence();
        }
        return essenceInPouches;
    }

    public void setPouches() {
        Optional<Widget> smallpouch = Inventory.search().withId(ItemID.SMALL_POUCH).first();
        Optional<Widget> medpouch = Inventory.search().withId(ItemID.MEDIUM_POUCH).first();
        Optional<Widget> largepouch = Inventory.search().withId(ItemID.LARGE_POUCH).first();
        Optional<Widget> giantpouch = Inventory.search().withId(ItemID.GIANT_POUCH).first();
        Optional<Widget> collosalpouch = Inventory.search().withId(ItemID.COLOSSAL_POUCH).first();
        if (smallpouch.isPresent()){
            Pouch smallEssPouch = new Pouch(ItemID.SMALL_POUCH,  3);
            pouches.add(smallEssPouch);
        }

        if (medpouch.isPresent() && client.getRealSkillLevel(Skill.RUNECRAFT) >= 25) {
            Pouch medEssPouch = new Pouch(ItemID.MEDIUM_POUCH,6);
            pouches.add(medEssPouch);
        }

        if (largepouch.isPresent() && client.getRealSkillLevel(Skill.RUNECRAFT) >= 50) {
            Pouch largeEssPouch = new Pouch(ItemID.LARGE_POUCH,9);
            pouches.add(largeEssPouch);
        }

        if (giantpouch.isPresent() && client.getRealSkillLevel(Skill.RUNECRAFT) >= 75) {
            Pouch giantEssPouch = new Pouch(ItemID.GIANT_POUCH ,12);
            pouches.add(giantEssPouch);
        }

        if (collosalpouch.isPresent() && client.getRealSkillLevel(Skill.RUNECRAFT) >= 85) {
            Pouch colossalEssPouch = new Pouch(ItemID.COLOSSAL_POUCH ,40);
            pouches.add(colossalEssPouch);
        }
    }

    public boolean isPouchFull(Pouch pouch) {
        return pouch.getCurrentEssence()==pouch.getEssenceTotal();
    }

    private int getRemainingEssence() {
        int total = 0;
        for(Pouch pouch : pouches){
            total += pouch.getEssenceTotal() - pouch.getCurrentEssence();
        }
        return total;

    }

    public List<Pouch> getEmptyPouches(){
        List<Pouch> result = new ArrayList<Pouch>();
        for (Pouch pouch: pouches) {
            if (!isPouchFull(pouch)) {
                result.add(pouch);
            }
        }
        return result;
    }

    public List<Pouch> getFullPouches(){
        List<Pouch> result = new ArrayList<>();
        for (Pouch pouch: pouches) {
            if (isPouchFull(pouch)) {
                result.add(pouch);
            }
        }
        return result;
    }

    public void fillPouches() {
        int essenceAmount = Inventory.getItemAmount(ItemID.GUARDIAN_ESSENCE);
        List<Pouch> result = getEmptyPouches();
        for (Pouch pouch : result) {
            Optional<Widget> emptyPouch = Inventory.search().withId(pouch.getPouchID()).first();
            if (emptyPouch.isPresent()) {
                InventoryInteraction.useItem(emptyPouch.get(), "Fill");
                if (essenceAmount - (pouch.getEssenceTotal() - pouch.getCurrentEssence()) > 0) {
                    pouch.setCurrentEssence(pouch.getEssenceTotal());
                    essenceAmount = Inventory.getItemAmount(ItemID.GUARDIAN_ESSENCE);
                } else {
                    pouch.setCurrentEssence(essenceAmount+pouch.getCurrentEssence());
                    essenceAmount =0;
                }

            }
        }
        if (isInHugeMine()) {
            mineHugeGuardians();
            return;
        }
        craftEssence();
    }

    public void emptyPouches(){
        int spaces = Inventory.getEmptySlots();
        List<Pouch> result = getFullPouches();
        for(Pouch pouch:result){
            Optional<Widget> emptyPouch = Inventory.search().withId(pouch.getPouchID()).first();
            if(emptyPouch.isPresent()){
                InventoryInteraction.useItem(emptyPouch.get(),"Empty");
                pouch.setCurrentEssence(pouch.getCurrentEssence()-spaces);
            }
        }
    }

    public boolean arePouchesFull(){
        for (Pouch pouch:pouches){
            if(pouch.getCurrentEssence()!=pouch.getEssenceTotal()) return false;
        }
        return true;
    }


    public int getEssenceSlots(WidgetInfo widgetInfo) {
        List<Widget> inventoryItems = Arrays.asList(client.getWidget(widgetInfo.getId()).getDynamicChildren());
        return (int) inventoryItems.stream().filter(item -> item.getItemId() == ItemID.PURE_ESSENCE || item.getItemId()
                == ItemID.RUNE_ESSENCE).count();
    }

    public boolean pouchesDegraded() {
        return api.getItemFromList(new int[]{ItemID.MEDIUM_POUCH_5511, ItemID.LARGE_POUCH_5513, ItemID.GIANT_POUCH_5515,
                ItemID.COLOSSAL_POUCH_26786}, WidgetInfo.INVENTORY) != null;
    }

    private boolean isPortalSpawned() {
        return ObjectUtil.nameContainsNoCase(Constants.PORTAL).filter(tileObject -> tileObject.getWorldLocation().getY() > Constants.OUTSIDE_BARRIER_Y).nearestToPlayer().isPresent();
    }

    private boolean hasGuardianEssenceAmount(int amount) {
        return InventoryUtil.getItemAmount(Constants.ESS, false) >= amount;
    }

    private boolean hasGuardianEssence() {
        int amt = InventoryUtil.getItemAmount(Constants.ESS, false);
        return !Inventory.search().withId(ItemID.GUARDIAN_ESSENCE).empty() && Inventory.full();
    }


    private boolean hasAnyGuardianEssence() {
        return InventoryUtil.getItemAmount(Constants.ESS, false) >= 1;
    }

    private boolean hasEnoughFrags() {
        return InventoryUtil.getItemAmount(Constants.FRAGS, true) >= config.minFrags();
    }

    private boolean hasEnoughStartingFrags() {
        return InventoryUtil.getItemAmount(Constants.FRAGS, true) >= config.startingFrags();
    }

    private boolean isWidgetVisible() {
        Optional<Widget> widget = Widgets.search().withId(Constants.PARENT_WIDGET).first();
        return widget.isPresent() && !widget.get().isHidden();
    }

    private boolean isMining() {
        return MINING_ANIMATION_IDS.contains(client.getLocalPlayer().getAnimation());
    }

    private boolean isOutsideBarrier() {
        return client.getLocalPlayer().getWorldLocation().getY() <= Constants.OUTSIDE_BARRIER_Y && !isInAltar();
    }

    private boolean isInLargeMine() {
        return !isInAltar() && client.getLocalPlayer().getWorldLocation().getX() >= Constants.LARGE_MINE_X;
    }

    private boolean isInHugeMine() {
        return !isInAltar() && client.getLocalPlayer().getWorldLocation().getX() <= Constants.HUGE_MINE_X;
    }

    private boolean isGameBusy() {
        return isOutsideBarrier() && TileObjects.search().withId(Constants.BARRIER_BUSY_ID).nearestToPlayer().isPresent();
    }

    private boolean isInAltar() {
        for (int region : client.getMapRegions()) {
            if (GOTR_REGIONS.contains(region)) {
                return false;
            }
        }
        return true;
    }

    private int getFrags() {
        return InventoryUtil.getItemAmount(Constants.FRAGS, true);
    }

    private boolean isCraftingEss() {
        return client.getLocalPlayer().getAnimation() == 9365;
    }

    public long getElapsedTimeMs() {
        Duration duration = Duration.between(timer, Instant.now());
        return duration.toMillis() + pauseTime;
    }

    public String getElapsedTime() {
        if (!started) {
            long second = (pauseTime / 1000) % 60;
            long minute = (pauseTime / (1000 * 60)) % 60;
            long hour = (pauseTime / (1000 * 60 * 60)) % 24;
            return String.format("%02d:%02d:%02d", hour, minute, second);
        }
        Duration duration = Duration.between(timer, Instant.now());
        long durationInMillis = duration.toMillis() + pauseTime;
        long second = (durationInMillis / 1000) % 60;
        long minute = (durationInMillis / (1000 * 60)) % 60;
        long hour = (durationInMillis / (1000 * 60 * 60)) % 24;

        return String.format("%02d:%02d:%02d", hour, minute, second);
    }

    private final HotkeyListener toggle = new HotkeyListener(() -> config.toggle()) {
        @Override
        public void hotkeyPressed() {
            toggle();
        }
    };

    public void toggle() {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }
        started = !started;

        if (!started) {
            pauseTime = getElapsedTimeMs();
            breakHandler.stopPlugin(this);
        } else {
            breakHandler.startPlugin(this);
            timer = Instant.now();
        }
    }
}