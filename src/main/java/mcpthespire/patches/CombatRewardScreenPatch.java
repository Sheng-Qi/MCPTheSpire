package mcpthespire.patches;

import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.screens.CombatRewardScreen;
import com.evacipated.cardcrawl.modthespire.lib.*;
import com.evacipated.cardcrawl.modthespire.patcher.PatchingException;
import mcpthespire.GameStateListener;
import javassist.CannotCompileException;
import javassist.CtBehavior;

import java.util.ArrayList;

@SpirePatch(
        clz= CombatRewardScreen.class,
        method="rewardViewUpdate"
)
public class CombatRewardScreenPatch {

    private static int previousSelectableRewardCount = -1;


    @SpireInsertPatch(
            locator=Locator.class
    )
    public static void Insert(CombatRewardScreen _instance) {
        int selectableRewardCount = 0;
        for(RewardItem reward : _instance.rewards) {
            if (!reward.isDone) {
                selectableRewardCount++;
            }
        }

        if (selectableRewardCount != previousSelectableRewardCount) {
            GameStateListener.registerStateChange();
            previousSelectableRewardCount = selectableRewardCount;
        }
    }

    private static class Locator extends SpireInsertLocator {
        public int[] Locate(CtBehavior ctMethodToPatch) throws CannotCompileException, PatchingException {
            Matcher matcher = new Matcher.MethodCallMatcher(CombatRewardScreen.class, "setLabel");
            return LineFinder.findInOrder(ctMethodToPatch, new ArrayList<Matcher>(), matcher);
        }
    }
}
