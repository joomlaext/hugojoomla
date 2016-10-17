package com.thecoderscorner.web.hugojoomla;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JoomlaHugoConverter {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final JdbcTemplate template;
    private final String pathToOutput;
    private final NastyContentChecker nastyContentChecker;

    private final String SQL =  "select C.id as id, U.username as username, C.created as created, C.introtext as intro, " +
                                "       C.`fulltext` as full, D.path as path, C.title as title, C.alias as alias\n" +
                                "from tcc_content C, tcc_users U, tcc_categories D\n" +
                                "where C.created_by = U.id\n" +
                                "  and D.id = C.catid\n" +
                                "  and D.path <> 'uncategorised'\n";

    private final Template tomlTemplate;
    private final Multimap<Integer, String> tagsByName = LinkedListMultimap.create(100);

    public JoomlaHugoConverter(NastyContentChecker nastyContentChecker,
                               JdbcTemplate template, String pathToOutput) throws IOException {
        this.nastyContentChecker = nastyContentChecker;
        this.template = template;
        this.pathToOutput = pathToOutput;

        Configuration cfg = new Configuration(Configuration.getVersion());
        cfg.setClassLoaderForTemplateLoading(ClassLoader.getSystemClassLoader(), "/");
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);

        tomlTemplate = cfg.getTemplate("defaultPage.toml.ftl");

        buildTags();
    }

    private void buildTags() {
        String sqlTags ="select M.content_item_id as id, T.title as name\n" +
                        " from tcc_tags T, tcc_contentitem_tag_map M\n" +
                        " where T.id = M.tag_id\n" +
                        "   and M.type_alias = 'com_content.article'";

        List<TagInfo> tags = template.query(sqlTags, (resultSet, i) -> new TagInfo(
                resultSet.getString("name"),
                resultSet.getInt("id")
        ));

        tags.forEach(t-> tagsByName.put(t.getContentId(), t.getTagName()));
        logger.info("Loaded {} tags into system", tags.size());
    }


    public void performConversion() {
        try {
            logger.info("Starting conversion of Joomla database");

            List<JoomlaContent> content = template.query(SQL, (resultSet, i) -> new JoomlaContent(
                    resultSet.getInt("id"),
                    resultSet.getString("username"),
                    resultSet.getDate("created").toLocalDate(),
                    resultSet.getString("intro"),
                    resultSet.getString("full"),
                    resultSet.getString("path"),
                    resultSet.getString("title"),
                    resultSet.getString("alias")
            ));

            content.forEach(c-> {
                nastyContentChecker.checkForNastyContent(c);
                Path path = Paths.get(pathToOutput);
                logger.info("processing {} {}", c.getTitle(), c.getCategory());
                Path newPath = path.resolve(c.getCategory());
                newPath.toFile().mkdirs();
                buildTomlOutput(c, newPath.resolve(c.getId() + "-" + c.getAlias() + ".md"));
            });

            logger.info("Finished conversion of Joomla database");
        }
        catch(Exception e) {
            logger.error("Did not complete conversion", e);
        }
    }

    public void buildTomlOutput(JoomlaContent content, Path resolve)  {

        try {
            String tagsQuoted = tagsByName.get(content.getId()).stream()
                    .map(t -> "\"" + t + "\"")
                    .collect(Collectors.joining(", "));

            Map<String, Object> root = new HashMap<>();
            root.put("joomlaData", content);
            root.put("tags", tagsQuoted);
            root.put("body", urlSorter(content.getIntro() + "\n" + content.getBody()));
            tomlTemplate.process(root, new BufferedWriter(new FileWriter(resolve.toFile())));
        } catch (Exception e) {
            logger.error("Failed to generate file", e);
        }
    }

    private String urlSorter(String body) {
        String sqlForArticleLink = "select C.alias as alias, D.path as path\n" +
                                    "from tcc_content C, tcc_categories D\n" +
                                    "where C.id=? and C.catid = D.id\n";
        Pattern linkPattern = Pattern.compile("index.php.option=com_content.amp.view=article.amp.id=([0-9]*).amp.catid=([0-9]*).amp.Itemid=([0-9]*)");

        boolean foundSomething = true;
        while(foundSomething) {
            Matcher matcher = linkPattern.matcher(body);
            if (matcher.find()) {
                int id = Integer.parseInt(matcher.group(1));
                String url = template.queryForObject(sqlForArticleLink, new Object[]{id}, (resultSet, i) ->
                        "/" + resultSet.getString("path") + "/" + id + "-" + resultSet.getString("alias"));
                body = body.replace(matcher.group(0), url);
                logger.info("  Rewrote url {}", url);
            }
            else {
                foundSomething = false;
            }
        }
        return body;
    }

    class TagInfo {
        private final String tagName;
        private final int contentId;

        public TagInfo(String tagName, int contentId) {
            this.tagName = tagName;
            this.contentId = contentId;
        }

        public int getContentId() {
            return contentId;
        }

        public String getTagName() {
            return tagName;
        }
    }
}