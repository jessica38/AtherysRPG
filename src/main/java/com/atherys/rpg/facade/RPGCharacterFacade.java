package com.atherys.rpg.facade;

import com.atherys.rpg.api.damage.AtherysDamageType;
import com.atherys.rpg.api.skill.RPGSkill;
import com.atherys.rpg.config.AtherysRPGConfig;
import com.atherys.rpg.api.stat.AttributeType;
import com.atherys.rpg.character.PlayerCharacter;
import com.atherys.rpg.command.exception.RPGCommandException;
import com.atherys.rpg.service.DamageService;
import com.atherys.rpg.service.ExpressionService;
import com.atherys.rpg.service.HealingService;
import com.atherys.rpg.service.RPGCharacterService;
import com.atherys.skills.api.event.ResourceRegenEvent;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.EntityType;
import org.spongepowered.api.entity.Equipable;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.cause.entity.damage.source.EntityDamageSource;
import org.spongepowered.api.event.cause.entity.damage.source.IndirectEntityDamageSource;
import org.spongepowered.api.event.entity.DamageEntityEvent;
import org.spongepowered.api.item.ItemType;
import org.spongepowered.api.item.ItemTypes;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.equipment.EquipmentTypes;
import org.spongepowered.api.service.permission.SubjectData;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.util.Tristate;

import java.util.Map;
import java.util.Optional;

@Singleton
public class RPGCharacterFacade {

    @Inject
    private AtherysRPGConfig config;

    @Inject
    private DamageService damageService;

    @Inject
    private HealingService healingService;

    @Inject
    private ExpressionService expressionService;

    @Inject
    private RPGCharacterService characterService;

    @Inject
    private AttributeFacade attributeFacade;

    @Inject
    private RPGSkillFacade skillFacade;

    @Inject
    private SkillGraphFacade skillGraphFacade;

    @Inject
    private RPGMessagingFacade rpgMsg;

    public void showPlayerExperience(Player player) {
        PlayerCharacter pc = characterService.getOrCreateCharacter(player);
        rpgMsg.info(player, Text.of(TextColors.DARK_GREEN, "Your current experience: ", TextColors.GOLD, pc.getExperience()));
    }

    public void addPlayerExperience(Player player, double amount) throws RPGCommandException {
        PlayerCharacter pc = characterService.getOrCreateCharacter(player);

        if (validateExperience(pc.getExperience() + amount)) {
            characterService.addExperience(pc, amount);
        }
    }

    public void removePlayerExperience(Player player, double amount) throws RPGCommandException {
        PlayerCharacter pc = characterService.getOrCreateCharacter(player);

        if (validateExperience(pc.getExperience() - amount)) {
            characterService.removeExperience(pc, amount);
        }
    }

    public void displaySkills(Player player) {
        PlayerCharacter pc = characterService.getOrCreateCharacter(player);

        Text.Builder skills = Text.builder().append(Text.of("Skills", Text.NEW_LINE));
        pc.getSkills().forEach(s -> {
            RPGSkill skill = skillFacade.getSkillById(s).get();
            skills.append(skillFacade.renderSkill(skill, player), Text.NEW_LINE);
        });

        player.sendMessage(skills.build());
    }

    public void getAvailableSkills(Player player) {
        PlayerCharacter pc = characterService.getOrCreateCharacter(player);

        Text.Builder skills = Text.builder().append(Text.of("Available Skills", Text.NEW_LINE));
        skillGraphFacade.getLinkedSkills(pc.getSkills()).forEach(s -> {
            skills.append(skillFacade.renderSkill(s, player), Text.NEW_LINE);
        });

        player.sendMessage(skills.build());
    }

    public void checkTreeOnLogin(Player player) {
        PlayerCharacter pc = characterService.getOrCreateCharacter(player);

        if (!skillGraphFacade.isPathValid(pc.getSkills())) {
            characterService.resetCharacter(pc);
            characterService.addSkill(pc, skillGraphFacade.getSkillGraphRoot().getId());
            rpgMsg.info(player, "The server's skill tree has changed. Your attributes and skill tree have been reset.");
        }
    }

    public void chooseSkill(Player player, String skillId) throws RPGCommandException {
        RPGSkill skill = skillFacade.getSkillById(skillId).orElseThrow(() -> {
            return new RPGCommandException("No skill with ID ", skillId, " found.");
        });

        PlayerCharacter pc = characterService.getOrCreateCharacter(player);

        double cost = skillGraphFacade.getCostForSkill(skill, pc.getSkills()).orElseThrow(() -> {
            return new RPGCommandException("You do not have access to that skill.");
        });

        if (pc.getExperience() >= cost)  {
            characterService.addSkill(pc, skillId);
            characterService.removeExperience(pc, cost);
            player.getSubjectData().setPermission(SubjectData.GLOBAL_CONTEXT, skillId, Tristate.TRUE);
            rpgMsg.info(player, "You have unlocked the skill, ", TextColors.GOLD, skill.getName(), ".");
        } else {
            throw new RPGCommandException("You do not have enough experience to unlock that skill.");
        }
    }

    private boolean validateExperience(double experience) throws RPGCommandException {
        if (experience < config.EXPERIENCE_MIN) {
            throw new RPGCommandException("A player cannot have experience less than ", config.EXPERIENCE_MIN);
        }

        if (experience > config.EXPERIENCE_MAX) {
            throw new RPGCommandException("A player cannot have experience bigger than ", config.EXPERIENCE_MAX);
        }

        return true;
    }

    public void setPlayerExperienceSpendingLimit(Player player, Double amount) {
        PlayerCharacter pc = characterService.getOrCreateCharacter(player);
        characterService.setCharacterExperienceSpendingLimit(pc, amount);
    }

    public void onResourceRegen(ResourceRegenEvent event, Player player) {
        PlayerCharacter pc = characterService.getOrCreateCharacter(player);

        // TODO: Account for items

        double amount = characterService.calcResourceRegen(pc.getBaseAttributes());
        event.setRegenAmount(amount);
    }

    public void onDamage(DamageEntityEvent event, EntityDamageSource rootSource) {
        // The average time taken for these, once the JVM has had time to do some runtime optimizations
        // is 0.2 - 0.3 milliseconds
        if (rootSource instanceof IndirectEntityDamageSource) {
            onIndirectDamage(event, (IndirectEntityDamageSource) rootSource);
        } else {
            onDirectDamage(event, rootSource);
        }
    }

    private void onDirectDamage(DamageEntityEvent event, EntityDamageSource rootSource) {
        Entity source = rootSource.getSource();
        Entity target = event.getTargetEntity();

        ItemType weaponType = ItemTypes.NONE;

        if (source instanceof Equipable) {
            weaponType = ((Equipable) source).getEquipped(EquipmentTypes.MAIN_HAND)
                    .map(ItemStack::getType)
                    .orElse(ItemTypes.NONE);
        }

//        characterService.updateCachedEntity(source);
//        characterService.updateCachedEntity(target);

        Map<AttributeType, Double> attackerAttributes = attributeFacade.getAllAttributes(source);
        Map<AttributeType, Double> targetAttributes = attributeFacade.getAllAttributes(target);

        event.setBaseDamage(damageService.getMeleeDamage(attackerAttributes, targetAttributes, weaponType));
    }

    private void onIndirectDamage(DamageEntityEvent event, IndirectEntityDamageSource rootSource) {
        Entity source = rootSource.getIndirectSource();
        Entity target = event.getTargetEntity();

//        characterService.updateCachedEntity(source);
//        characterService.updateCachedEntity(target);

        Map<AttributeType, Double> attackerAttributes = attributeFacade.getAllAttributes(source);
        Map<AttributeType, Double> targetAttributes = attributeFacade.getAllAttributes(target);

        EntityType projectileType = rootSource.getSource().getType();

        event.setBaseDamage(damageService.getRangedDamage(attackerAttributes, targetAttributes, projectileType));
    }

// Healing is currently not implementable as such due to Sponge
//    public void onHeal(ChangeDataHolderEvent.ValueChange event) {
//        if (event.getTargetHolder() instanceof Living) {
//            Living living = (Living) event.getTargetHolder();
//            RPGCharacter<?> character = characterService.getOrCreateCharacter(living);
//
//            double healthRegenAmount = healingService.getHealthRegenAmount(character);
//            System.out.println("New health regen amount: " + healthRegenAmount);
//
//            HealthData healthData = living.getHealthData();
//            healthData.transform(Keys.HEALTH, (value) -> value + healthRegenAmount);
//
//            event.proposeChanges(DataTransactionResult.successResult(healthData.health().asImmutable()));
//        }
//    }

}
