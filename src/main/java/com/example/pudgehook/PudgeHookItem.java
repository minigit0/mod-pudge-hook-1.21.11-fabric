package com.example.pudgehook;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

public class PudgeHookItem extends Item {
    private static final double RANGE = 20.0;      // дальность хука в блоках
    private static final int COOLDOWN_TICKS = 60;  // кд: 60 тиков = 3 секунды
    private static final double PULL_SPEED = 3;  // сила притягивания

    public PudgeHookItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return ActionResult.SUCCESS;
        }
        ItemStack stack = user.getStackInHand(hand);

        Vec3d start = user.getEyePos();
        Vec3d look = user.getRotationVec(1.0F);
        Vec3d end = start.add(look.multiply(RANGE));

        // луч до ближайшего блока (хук не летит сквозь стены)
        BlockHitResult blockHit = world.raycast(new RaycastContext(
                start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, user));
        Vec3d limit = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getPos();

        Box box = user.getBoundingBox().stretch(look.multiply(RANGE)).expand(1.0);
        double maxDistSq = start.squaredDistanceTo(limit);
        EntityHitResult hit = ProjectileUtil.raycast(user, start, limit, box,
                e -> e instanceof LivingEntity && !e.isSpectator() && e.isAlive() && e != user,
                maxDistSq);

        Vec3d lineEnd = hit != null ? hit.getPos() : limit;

        // "цепь" из частиц
        double len = start.distanceTo(lineEnd);
        for (double d = 1.0; d < len; d += 0.5) {
            Vec3d p = start.add(look.multiply(d));
            serverWorld.spawnParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        world.playSound(null, user.getX(), user.getY(), user.getZ(),
                SoundEvents.ENTITY_FISHING_BOBBER_THROW, SoundCategory.PLAYERS, 1.0F, 0.6F);

        if (hit != null) {
           Entity target = hit.getEntity();
            if (target instanceof LivingEntity living) {
                living.takeKnockback(PULL_SPEED, target.getX() - user.getX(), target.getZ() - user.getZ());
            }
            world.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1.0F, 0.8F);
        }

        // кулдаун после использования (даже при промахе, как в доте)
        user.getItemCooldownManager().set(stack, COOLDOWN_TICKS);
        return ActionResult.CONSUME;
    }
}
