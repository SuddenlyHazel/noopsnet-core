// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
@GameTestHolder("noopsnet_core")
@PrefixGameTestTemplate(false)
public class CoreGameTests {
    @GameTest(template="empty",timeoutTicks=100)
    public static void commands(GameTestHelper h) throws Exception {
        var level=h.getLevel();var server=level.getServer();
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"CoreFixture"));
        BlockPos floor=h.absolutePos(new BlockPos(2,1,2));
        level.setBlockAndUpdate(floor,Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(floor.above(),Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(floor.above(2),Blocks.AIR.defaultBlockState());
        player.setPos(floor.getX()+.5,floor.getY()+1,floor.getZ()+.5);
        var dispatcher=server.getCommands().getDispatcher();var source=player.createCommandSourceStack().withPermission(0);
        h.assertTrue(dispatcher.execute("home",source)==0,"No home initially");
        h.assertTrue(dispatcher.execute("sethome old",source)==1,"Non-OP may set home");
        h.assertTrue(dispatcher.execute("sethome cabin",source)==1,"Replace one home");
        h.assertTrue(dispatcher.execute("home old",source)==0,"Old name removed");
        level.setBlockAndUpdate(floor.above(),Blocks.STONE.defaultBlockState());
        h.assertTrue(dispatcher.execute("home cabin",source)==0,"Blocked destination rejected");
        level.setBlockAndUpdate(floor.above(),Blocks.AIR.defaultBlockState());
        h.assertTrue(dispatcher.execute("home cabin",source)==1,"Failed travel did not consume cooldown");
        h.assertTrue(dispatcher.execute("home",source)==0,"Home cooldown enforced");
        var oldSpawn=level.getSharedSpawnPos();float angle=level.getSharedSpawnAngle();
        level.setDefaultSpawnPos(floor.above(),0);
        try {
            h.assertTrue(dispatcher.execute("spawn",source)==1,"Spawn cooldown independent");
            h.assertTrue(dispatcher.execute("spawn",source)==0,"Spawn cooldown enforced");
        } finally { level.setDefaultSpawnPos(oldSpawn,angle); }
        h.assertTrue(dispatcher.execute("delhome cabin",source)==1,"Delete named home");
        h.assertTrue(dispatcher.execute("home",source)==0,"Deleted home unavailable");
        h.succeed();
    }
}
