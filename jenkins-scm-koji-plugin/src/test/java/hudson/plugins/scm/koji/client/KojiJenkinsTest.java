/*
 * The MIT License
 *
 * Copyright 2018 zzambers.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.scm.koji.client;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.plugins.scm.koji.KojiSCM;
import hudson.plugins.scm.koji.model.KojiScmConfig;
import hudson.tasks.Shell;
import java.io.File;
import java.io.FileInputStream;
import org.fakekoji.xmlrpc.server.JavaServer;
import org.fakekoji.xmlrpc.server.JavaServerConstants;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;
import static org.junit.Assert.assertEquals;


public class KojiJenkinsTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    public void runTest(KojiScmConfig config, String shellScript, boolean successExpected) throws Exception {
        /* prepare directory structure for fake-koji with some dummy builds */
        File tmpDir = temporaryFolder.newFolder();
        tmpDir.mkdir();
        File localBuilds = new File(tmpDir, "local-builds");
        File upstreamRepos = new File(tmpDir, "upstream-repos");
        KojiListBuildsTest.generateFakeKojiData(localBuilds, upstreamRepos);
        /* create fake koji server */
        JavaServer javaServer = new JavaServer(localBuilds, upstreamRepos,
                JavaServer.DFAULT_RP2C_PORT, JavaServer.DFAULT_DWNLD_PORT,
                JavaServer.DFAULT_SSHUPLOAD_PORT, 8080);
        try {
            /* start fake-koji server */
            javaServer.start();
            /* create new jenkins free style project */
            FreeStyleProject project = j.createFreeStyleProject();

            /* parameters for KojiSCM plugin */
            String kojiTopUrl = config.getKojiTopUrl();
            String kojiDownloadUrl = config.getKojiDownloadUrl();
            String packageName = config.getPackageName();
            String arch = config.getArch();
            String tag = config.getTag();
            String excludeNvr = config.getExcludeNvr();
            String downloadDir = config.getDownloadDir();
            boolean cleanDownloadDir = config.isCleanDownloadDir();
            boolean dirPerNvr = config.isDirPerNvr();
            int maxPreviousBuilds = config.getMaxPreviousBuilds();

            /* create KojiSCM plugin instance */
            KojiSCM scm = new KojiSCM(kojiTopUrl, kojiDownloadUrl,
                    packageName, arch, tag, excludeNvr,
                    downloadDir, cleanDownloadDir, dirPerNvr,
                    maxPreviousBuilds);
            /* set new KojiSCM plugin instance as scm for project */
            project.setScm(scm);
            /* set shell string executed by the project */
            project.getBuildersList().add(new Shell(shellScript));
            /* schedule build and get it (wait for it to finish) */
            FreeStyleBuild build = project.scheduleBuild2(0).get();
            /* Print log (of the build) to stdout */
            try (FileInputStream fis = new FileInputStream(build.getLogFile())) {
                for (;;) {
                    int readByte = fis.read();
                    if (readByte < 0) {
                        break;
                    }
                    System.out.write(readByte);
                }
            }
            /* get result of the build and check it it meets expectations */
            Result result = build.getResult();
            assertEquals(successExpected, result == Result.SUCCESS);
        } finally {
            /* always stop fake-koji */
            javaServer.stop();
        }
    }

    @Test
    public void testExistingBuild() throws Exception {
        /* Test koji scm plugin on existing fake-koji build(s) 
           -> should end with success */
        KojiScmConfig config = new KojiScmConfig(
                "http://localhost:" + JavaServerConstants.xPortAxiom + "/RPC2",
                "http://localhost:" + JavaServerConstants.dPortAxiom,
                "java-1.8.0-openjdk",
                "x86_64,src",
                "fastdebug-f24*",
                null,
                null,
                false,
                false,
                10
        );
        String shellString = "find . | grep \"java-1.8.0-openjdk.*x86_64.tarxz\"\n"
                + "find . | grep \"java-1.8.0-openjdk.*src.tarxz\"";
        runTest(config, shellString, true);
    }

    @Test
    public void testNonExistingBuild() throws Exception {
        /* Test koji scm plugin on non-existing fake-koji build(s)
           -> should not end with success */
        KojiScmConfig config = new KojiScmConfig(
                "http://localhost:" + JavaServerConstants.xPortAxiom + "/RPC2",
                "http://localhost:" + JavaServerConstants.dPortAxiom,
                "non-existing-build",
                "x86_64,src",
                "fastdebug-f24*",
                null,
                null,
                false,
                false,
                10
        );
        String shellString = "! find . | grep \".*tarxz\"";
        runTest(config, shellString, false);
    }
}
