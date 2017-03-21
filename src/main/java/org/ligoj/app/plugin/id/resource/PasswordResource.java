package org.ligoj.app.plugin.id.resource;

import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.mail.Message;
import javax.mail.internet.InternetAddress;
import javax.transaction.Transactional;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;

import org.apache.commons.lang3.CharEncoding;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.joda.time.DateTime;
import org.ligoj.app.api.SimpleUserLdap;
import org.ligoj.app.api.UserLdap;
import org.ligoj.app.iam.IUserRepository;
import org.ligoj.app.iam.IamProvider;
import org.ligoj.app.plugin.id.dao.PasswordResetRepository;
import org.ligoj.app.plugin.id.model.PasswordReset;
import org.ligoj.app.plugin.mail.resource.MailServicePlugin;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * LDAP password resource.
 */
@Path("/id/password")
@Service
@Transactional
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
public class PasswordResource {

	private static final String MAIL_NODE = "password.mail.node";
	private static final String URL_PUBLIC = "password.mail.url";
	private static final String SUBJECT = "password.mail.reset.subject";
	private static final String MESSAGE_RESET = "password.mail.reset.content";
	private static final String MESSAGE_NEW_SUBJECT = "password.mail.new.subject";
	private static final String MESSAGE_NEW = "password.mail.new.content";
	private static final String MESSAGE_FROM_TITLE = "password.mail.from.title";
	private static final String MESSAGE_FROM = "password.mail.from";

	/**
	 * IAM provider.
	 */
	@Autowired
	protected IamProvider iamProvider;

	@Autowired
	protected PasswordResetRepository repository;

	@Autowired
	protected ConfigurationResource configurationResource;

	@Autowired
	protected ServicePluginLocator servicePluginLocator;

	/**
	 * Generate a random password.
	 * 
	 * @return a generated password.
	 */
	public String generate() {
		return RandomStringUtils.randomAlphanumeric(10);
	}

	/**
	 * Update user password for current user.
	 * 
	 * @param request
	 *            the user request.
	 */
	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	public void update(final ResetPassword request, @Context final SecurityContext context) {
		final String login = context.getUserPrincipal().getName();

		// Check user and password
		if (!getUser().authenticate(login, request.getPassword())) {
			throw new ValidationJsonException("password", "login");
		}

		// Update password
		create(login, request.getNewPassword(), false);
	}

	/**
	 * Reset password from a mail challenge :token + mail + user name.
	 * 
	 * @param request
	 *            the user request.
	 * @param uid
	 *            the user UID.
	 */
	@POST
	@Path("reset/{uid}")
	@Consumes(MediaType.APPLICATION_JSON)
	public void reset(final ResetPasswordByMailChallenge request, @PathParam("uid") final String uid) {
		// check token in database : Invalid token, or out-dated, or invalid user ?
		final PasswordReset passwordReset = repository.findByLoginAndTokenAndDateAfter(uid, request.getToken(),
				DateTime.now().minusHours(NumberUtils.INTEGER_ONE).toDate());
		if (passwordReset == null) {
			throw new BusinessException(BusinessException.KEY_UNKNOW_ID);
		}

		// Check the user and update his/her password
		create(uid, request.getPassword(), false);

		// Remove password reset request since this token is no more valid
		repository.delete(passwordReset);
	}

	/**
	 * Manage user password recovery with valid user name and mail.
	 * 
	 * @param uid
	 *            user identifier.
	 * @param mail
	 *            user mail to match.
	 */
	@POST
	@Path("recovery/{uid}/{mail}")
	public void requestRecovery(@PathParam("uid") final String uid, @PathParam("mail") final String mail) {
		// Check user exists and is not locked
		final UserLdap userLdap = getUser().findById(uid);
		if (userLdap != null && userLdap.getLocked() == null) {
			// Case insensitive match
			final Set<String> mails = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
			mails.addAll(userLdap.getMails());
			if (!mails.add(mail) && repository.findByLoginAndDateAfter(uid, DateTime.now().minusMinutes(5).toDate()) == null) {
				// We accept password reset only if no request has been done for 5 minutes
				createPasswordReset(uid, mail, userLdap, UUID.randomUUID().toString());
			}
		}
	}

	/**
	 * Create a password reset. Previous token are kept.
	 */
	private void createPasswordReset(final String uid, final String mail, final UserLdap userLdap, final String token) {
		final PasswordReset passwordReset = new PasswordReset();
		passwordReset.setLogin(uid);
		passwordReset.setToken(token);
		passwordReset.setDate(new Date());
		repository.saveAndFlush(passwordReset);
		sendMailReset(userLdap, mail, token);
	}

	/**
	 * Send mail for reset request
	 * 
	 * @param user
	 *            User account.
	 * @param mailTo
	 *            Recipient's mail.
	 * @param token
	 *            Random token.
	 */
	protected void sendMailReset(final UserLdap user, final String mailTo, final String token) {
		sendMail(mimeMessage -> {
			final String fullName = user.getFirstName() + " " + user.getLastName();
			final InternetAddress internetAddress = new InternetAddress(mailTo, fullName, CharEncoding.UTF_8);
			String link = configurationResource.get(URL_PUBLIC) + "#reset=" + token + "/" + user.getId();
			link = "<a href=\"" + link + "\">" + link + "</a>";
			mimeMessage.setHeader("Content-Type", "text/plain; charset=UTF-8");
			mimeMessage.setFrom(
					new InternetAddress(configurationResource.get(MESSAGE_FROM), configurationResource.get(MESSAGE_FROM_TITLE), CharEncoding.UTF_8));
			mimeMessage.setRecipient(Message.RecipientType.TO, internetAddress);
			mimeMessage.setSubject(configurationResource.get(SUBJECT), CharEncoding.UTF_8);
			mimeMessage.setContent(String.format(configurationResource.get(MESSAGE_RESET), fullName, link, fullName, link),
					"text/html; charset=UTF-8");
		});
	}

	/**
	 * Send an email using the default mail node. If no mail is configured, nothing happens.
	 */
	private void sendMail(final MimeMessagePreparator preparator) {
		final String node = configurationResource.get(MAIL_NODE);
		Optional.ofNullable(servicePluginLocator.getResource(node, MailServicePlugin.class)).map(p->p.send(node, preparator));
	}

	/**
	 * Daily, clean old recovery requests.
	 */
	@Scheduled(cron = "0 0 1 1/1 * ?")
	public void cleanRecoveries() {
		// @Modifying + @Scheduled + @Transactional [+protected] --> No TX, wait for next release & TU
		SpringUtils.getBean(PasswordResource.class).cleanRecoveriesInternal();
	}

	/**
	 * Clean old recovery requests
	 */
	public void cleanRecoveriesInternal() {
		repository.deleteByDateBefore(DateTime.now().minusDays(1).toDate());
	}

	/**
	 * Set a generated password of given user (UID) and return the generated one. This password is stored as digested in
	 * corresponding LDAP entry.
	 * 
	 * @param uid
	 *            LDAP UID of user.
	 * @return the clear generated password.
	 */
	public String generate(final String uid) {
		return create(uid, generate());
	}

	/**
	 * Set the password of given user (UID) and return the generated one. This password is stored as digested in
	 * corresponding LDAP entry.
	 * 
	 * @param uid
	 *            LDAP UID of user.
	 * @param password
	 *            The password to set.
	 * @return the clear generated password.
	 */
	protected String create(final String uid, final String password) {
		return create(uid, password, true);
	}

	/**
	 * Set the password of given user (UID) and return the generated one. This password is stored as digested in
	 * corresponding LDAP entry.
	 * 
	 * @param uid
	 *            LDAP UID of user.
	 * @param password
	 *            The password to set.
	 * @param sendMail
	 *            send a mail if true.
	 * @return the clear generated password.
	 */
	protected String create(final String uid, final String password, final boolean sendMail) {
		final UserLdap userLdap = checkUser(uid);

		// Replace the old or create a new one
		getUser().setPassword(userLdap, password);
		if (sendMail) {
			sendMailPassword(userLdap, password);
		}
		return password;
	}

	/**
	 * Check the user exists.
	 * 
	 * @param uid
	 *            UID of user to lookup.
	 * @return {@link UserLdap} LDAP entry.
	 */
	private UserLdap checkUser(final String uid) {
		final UserLdap userLdap = getUser().findById(uid);
		if (userLdap == null || userLdap.getLocked() != null) {
			throw new BusinessException(BusinessException.KEY_UNKNOW_ID, uid);
		}
		return userLdap;
	}

	/**
	 * Send the mail of password to the user.
	 */
	protected void sendMailPassword(final SimpleUserLdap user, final String password) {
		log.info("Sending mail to '{}' at {}", user.getId(), user.getMails());
		sendMail(mimeMessage -> {
			final InternetAddress[] internetAddresses = new InternetAddress[user.getMails().size()];
			final String fullName = user.getFirstName() + " " + user.getLastName();
			final String link = "<a href=\"" + configurationResource.get(URL_PUBLIC) + "\">" + configurationResource.get(URL_PUBLIC) + "</a>";
			mimeMessage.setHeader("Content-Type", "text/plain; charset=UTF-8");
			mimeMessage.setFrom(
					new InternetAddress(configurationResource.get(MESSAGE_FROM), configurationResource.get(MESSAGE_FROM_TITLE), CharEncoding.UTF_8));
			for (int i = 0; i < user.getMails().size(); i++) {
				internetAddresses[i] = new InternetAddress(user.getMails().get(i), fullName, CharEncoding.UTF_8);
			}
			mimeMessage.setSubject(String.format(configurationResource.get(MESSAGE_NEW_SUBJECT), fullName), CharEncoding.UTF_8);
			mimeMessage.setRecipients(Message.RecipientType.TO, internetAddresses);
			mimeMessage.setContent(String.format(configurationResource.get(MESSAGE_NEW), fullName, user.getId(), password, link, fullName,
					user.getId(), password, link), "text/html; charset=UTF-8");
		});
	}

	/**
	 * User repository provider.
	 * 
	 * @return User repository provider.
	 */
	protected IUserRepository getUser() {
		return iamProvider.getConfiguration().getUserRepository();
	}

}