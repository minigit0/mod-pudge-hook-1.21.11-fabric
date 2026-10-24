package com.example.pudgehook;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class PudgeHookItem extends Item {
    private static final double RANGE = 20.0;          // дальность хука в блоках
    private static final double HOOK_SPEED = 1.6;      // скорость полёта хука (блоков/тик)
    private static final double PULL_SPEED = 1.6;      // скорость притягивания (блоков/тик)
    private static final double STOP_DISTANCE = 1.6;   // на каком расстоянии от игрока останавливается цель
    private static final int COOLDOWN_TICKS = 60;      // кд после возвращения хука (3 сек)

    private static final Identifier FREEZE_ID = Identifier.of("pudgehook", "freeze");

    private enum Phase { FLYING, PULLING, RETURNING }

    private static class HookState {
        ServerWorld world;
        ItemStack stack;
        Vec3d pos;
        Vec3d dir;
        double travelled = 0;
        Phase phase = Phase.FLYING;
        UUID targetId;
        boolean targetHadNoGravity;
        boolean targetHadAiDisabled;
    }

    private static final Map<UUID, HookState> HOOKS = new HashMap<>();
    private static boolean registered = false;

    public PudgeHookItem(Settings settings) {
        super(settings);
        if (!registered) {
            registered = true;
            ServerTickEvents.END_SERVER_TICK.register(PudgeHookItem::tickAll);
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> HOOKS.clear());
        }
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return ActionResult.SUCCESS;
        }
        if (HOOKS.containsKey(user.getUuid())) {
            return ActionResult.FAIL; // хук уже в полёте
        }

        HookState s = new HookState();
        s.world = serverWorld;
        s.stack = user.getStackInHand(hand).copy();
        s.pos = user.getEyePos();
        s.dir = user.getRotationVec(1.0F).normalize();
        HOOKS.put(user.getUuid(), s);

        setFrozen(user, true); // владелец не двигается, пока хук не вернётся

        world.playSound(null, user.getX(), user.getY(), user.getZ(),
                SoundEvents.ENTITY_FISHING_BOBBER_THROW, SoundCategory.PLAYERS, 1.0F, 0.6F);
        return ActionResult.CONSUME;
    }

    // ---------- серверный тик ----------

    private static void tickAll(MinecraftServer server) {
        Iterator<Map.Entry<UUID, HookState>> it = HOOKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, HookState> entry = it.next();
            HookState s = entry.getValue();
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(entry.getKey());

            if (owner == null || owner.isRemoved() || !owner.isAlive()) {
                releaseTarget(s);
                if (owner != null) setFrozen(owner, false);
                it.remove();
                continue;
            }

            if (tickHook(owner, s)) {
                releaseTarget(s);
                setFrozen(owner, false);
                owner.getItemCooldownManager().set(s.stack, COOLDOWN_TICKS);
                s.world.playSound(null, owner.getX(), owner.getY(), owner.getZ(),
                        SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.8F, 1.2F);
                it.remove();
            }
        }
    }

    /** Возвращает true, когда хук закончил работу. */
    private static boolean tickHook(ServerPlayerEntity owner, HookState s) {
        ServerWorld world = s.world;
        Vec3d ownerCenter = new Vec3d(owner.getX(), owner.getY() + owner.getHeight() * 0.5, owner.getZ());

        if (s.phase == Phase.FLYING) {
            Vec3d from = s.pos;
            double step = Math.min(HOOK_SPEED, RANGE - s.travelled);
            Vec3d to = from.add(s.dir.multiply(step));

            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
            boolean hitBlock = blockHit.getType() != HitResult.Type.MISS;
            Vec3d limit = hitBlock ? blockHit.getPos() : to;

            Box box = new Box(from, limit).expand(1.0);
            EntityHitResult hit = ProjectileUtil.raycast(owner, from, limit, box,
                    e -> e instanceof LivingEntity && !e.isSpectator() && e.isAlive() && e != owner,
                    from.squaredDistanceTo(limit));

            if (hit != null && hit.getEntity() instanceof LivingEntity target) {
                // насаживаем цель на хук
                s.targetId = target.getUuid();
                s.targetHadNoGravity = target.hasNoGravity();
                target.setNoGravity(true);
                setFrozen(target, true);
                if (target instanceof MobEntity mob) {
                    s.targetHadAiDisabled = mob.isAiDisabled();
                    mob.setAiDisabled(true);
                }
                s.pos = new Vec3d(target.getX(), target.getY() + target.getHeight() * 0.5, target.getZ());
                s.phase = Phase.PULLING;
                world.playSound(null, target.getX(), target.getY(), target.getZ(),
                        SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1.0F, 0.8F);
            } else {
                s.travelled += from.distanceTo(limit);
                s.pos = limit;
                if (hitBlock || s.travelled >= RANGE - 1e-6) {
                    s.phase = Phase.RETURNING;
                }
            }
            drawChain(world, owner, s.pos);
            return false;
        }

        // PULLING / RETURNING: хук едет к владельцу
        Vec3d toOwner = ownerCenter.subtract(s.pos);
        double dist = toOwner.length();
        double stop = s.phase == Phase.PULLING ? STOP_DISTANCE : 1.0;

        if (dist <= stop + 0.01) {
            return true;
        }

        double move = Math.min(PULL_SPEED, dist - stop);
        s.pos = s.pos.add(toOwner.multiply(move / dist));

        if (s.phase == Phase.PULLING) {
            Entity target = s.targetId == null ? null : world.getEntity(s.targetId);
            if (target == null || !target.isAlive()) {
                s.phase = Phase.RETURNING;
            } else {
                target.requestTeleport(s.pos.x, s.pos.y - target.getHeight() * 0.5, s.pos.z);
                target.setVelocity(Vec3d.ZERO);
            }
        }

        drawChain(world, owner, s.pos);
        return false;
    }

    // ---------- вспомогательное ----------

    private static void drawChain(ServerWorld world, ServerPlayerEntity owner, Vec3d hookPos) {
        Vec3d start = owner.getEyePos().subtract(0, 0.3, 0);
        Vec3d diff = hookPos.subtract(start);
        double len = diff.length();
        if (len > 0.01) {
            Vec3d dir = diff.multiply(1.0 / len);
            for (double d = 0.5; d < len; d += 0.6) {
                Vec3d p = start.add(dir.multiply(d));
                world.spawnParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            }
        }
        // голова хука
        world.spawnParticles(ParticleTypes.ENCHANTED_HIT, hookPos.x, hookPos.y, hookPos.z, 4, 0.1, 0.1, 0.1, 0.0);
    }

    private static void releaseTarget(HookState s) {
        if (s.targetId == null) return;
        Entity t = s.world.getEntity(s.targetId);
        if (t instanceof LivingEntity le) {
            setFrozen(le, false);
            le.setNoGravity(s.targetHadNoGravity);
            if (le instanceof MobEntity mob) {
                mob.setAiDisabled(s.targetHadAiDisabled);
            }
            le.setVelocity(Vec3d.ZERO);
        }
        s.targetId = null;
    }

    /** Полностью блокирует ходьбу и прыжок через временные модификаторы атрибутов. */
    private static void setFrozen(LivingEntity e, boolean frozen) {
        applyFreeze(e.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED), frozen);
        applyFreeze(e.getAttributeInstance(EntityAttributes.JUMP_STRENGTH), frozen);
    }

    private static void applyFreeze(EntityAttributeInstance inst, boolean frozen) {
        if (inst == null) return;
        if (frozen) {
            if (!inst.hasModifier(FREEZE_ID)) {
                inst.addTemporaryModifier(new EntityAttributeModifier(
                        FREEZE_ID, -1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        } else {
            inst.removeModifier(FREEZE_ID);
        }
    }
}
