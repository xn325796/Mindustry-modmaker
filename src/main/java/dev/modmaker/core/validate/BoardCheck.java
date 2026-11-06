package dev.modmaker.core.validate;

import dev.modmaker.core.model.CanvasNode;
import dev.modmaker.core.model.ModProject;

/** Cards whose content record no longer exists cannot be rendered or written. */
final class BoardCheck implements ProjectCheck {

    @Override
    public String id() {
        return "boards";
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        for (var board : project.boards) {
            for (var node : board.nodes) {
                if (CanvasNode.KIND_CONTENT.equals(node.kind)
                    && node.contentId != null
                    && project.contentById(node.contentId) == null) {
                    context.report(Issue.warning("validate.board.danglingCard", board.name));
                }
            }
        }
    }
}
