package fi.alavesa.scpaccess.rig;

import fi.alavesa.scpaccess.model.BbModel;
import fi.alavesa.scpaccess.model.ModelGeometry;
import org.joml.Matrix4d;

/**
 * One item display of a rig: the cubes that move together.
 *
 * @param key      last part of the pack ids ({@code scpaccess:<rig>/<key>}); null for a card bone
 * @param group    the Blockbench group that animates it; null for the static rest of the model
 * @param card     true = shows whatever keycard is swiped instead of its own cubes (group named "card...")
 * @param state    the reader/sensor state it is visible in ("granted" for a group "state_granted"), or null = always
 * @param sub      its own cubes as a model (null for a card bone)
 * @param geometry fit of {@link #sub} (null for a card bone)
 * @param base     model px of the displayed item -> rig px; identity for ordinary bones, the card's placement for a card bone
 */
public record Bone(int index, String key, String name, BbModel.Group group, boolean card, String state,
                   BbModel sub, ModelGeometry geometry, Matrix4d base) { }
