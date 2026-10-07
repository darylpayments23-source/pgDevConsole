package com.example.deploymentconsole;
import com.example.deploymentconsole.service.SqlSplitter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SqlSplitterTest {
  @Test void handlesDollarQuotes() {
    var x=SqlSplitter.split("CREATE FUNCTION x() RETURNS void AS $$ BEGIN NULL; END; $$ LANGUAGE plpgsql; CREATE TABLE t(id int);");
    assertEquals(2,x.size());
  }
}
