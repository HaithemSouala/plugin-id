package org.ligoj.app.plugin.id.resource.batch;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.id.resource.ContainerScopeResource;
import org.ligoj.app.plugin.id.resource.GroupEditionVo;
import org.ligoj.app.plugin.id.resource.GroupResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Group LDAP import from list of bean entries.
 */
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class GroupFullLdapTask extends AbstractLdapBatchTask<GroupImportEntry> {

	@Autowired
	protected GroupResource resource;

	@Autowired
	protected ContainerScopeResource containerScopeResource;

	@Override
	protected void doBatch(final GroupImportEntry entry) throws Exception {

		// Copy the group information
		final GroupEditionVo edition = new GroupEditionVo();
		edition.setName(entry.getName());
		edition.setParent(StringUtils.trimToNull(entry.getParent()));
		edition.setScope(containerScopeResource.findByName(entry.getType()).getId());

		// Split muti-valued data
		edition.setAssistants(toList(entry.getAssistant()));
		edition.setDepartments(toList(entry.getDepartment()));
		edition.setOwners(toList(entry.getOwner()));

		// Create the user
		resource.create(edition);
	}

}